package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.config.RabbitMqConfig;
import com.karimmajed.flowforge.repository.OutboxDeliveryStore;
import com.karimmajed.flowforge.repository.OutboxDeliveryStore.PendingOutbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionOutboxPublisherTest {

    @Mock
    private OutboxDeliveryStore store;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private ExecutionOutboxPublisher publisher;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void confirmedPublishMarksTheEventAndReleasesTheClaim() {
        PendingOutbox event = new PendingOutbox(10L, 21L);
        when(store.claimBatch(anyString(), eq(5))).thenReturn(List.of(event));
        when(store.markPublished(eq(event), anyString())).thenReturn(true);
        completeSend("21", new CorrelationData.Confirm(true, null), false, true);

        publisher.publishPending();

        String token = claimedToken();
        verify(store).markPublished(event, token);
        verify(store).releaseClaim(10L, token);
    }

    @Test
    void rejectedConfirmLeavesTheEventPending() {
        PendingOutbox event = new PendingOutbox(10L, 21L);
        when(store.claimBatch(anyString(), eq(5))).thenReturn(List.of(event));
        completeSend("21", new CorrelationData.Confirm(false, "rejected"), false, false);

        publisher.publishPending();

        String token = claimedToken();
        verify(store, never()).markPublished(any(), any());
        verify(store).releaseClaim(10L, token);
    }

    @Test
    void unroutableMessageLeavesTheEventPending() {
        PendingOutbox event = new PendingOutbox(10L, 21L);
        when(store.claimBatch(anyString(), eq(5))).thenReturn(List.of(event));
        completeSend("21", new CorrelationData.Confirm(true, null), true, false);

        publisher.publishPending();

        String token = claimedToken();
        verify(store, never()).markPublished(any(), any());
        verify(store).releaseClaim(10L, token);
    }

    @Test
    void brokerFailureLeavesTheEventPending() {
        PendingOutbox event = new PendingOutbox(10L, 21L);
        when(store.claimBatch(anyString(), eq(5))).thenReturn(List.of(event));
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(
                        eq(RabbitMqConfig.EXCHANGE),
                        eq(RabbitMqConfig.ROUTING_KEY),
                        eq("21"),
                        any(MessagePostProcessor.class),
                        any(CorrelationData.class));

        publisher.publishPending();

        String token = claimedToken();
        verify(store, never()).markPublished(any(), any());
        verify(store).releaseClaim(10L, token);
    }

    @Test
    void lostClaimDoesNotTreatTheSendAsOwned() {
        PendingOutbox event = new PendingOutbox(10L, 21L);
        when(store.claimBatch(anyString(), eq(5))).thenReturn(List.of(event));
        when(store.markPublished(eq(event), anyString())).thenReturn(false);
        completeSend("21", new CorrelationData.Confirm(true, null), false, false);

        publisher.publishPending();

        String token = claimedToken();
        verify(store).markPublished(event, token);
        verify(store).releaseClaim(10L, token);
    }

    @Test
    void releaseFailureDoesNotStopTheRestOfTheBatch() {
        PendingOutbox first = new PendingOutbox(10L, 21L);
        PendingOutbox second = new PendingOutbox(11L, 22L);
        when(store.claimBatch(anyString(), eq(5)))
                .thenReturn(List.of(first, second));
        when(store.markPublished(any(), anyString())).thenReturn(true);
        doThrow(new RuntimeException("db"))
                .when(store).releaseClaim(eq(10L), anyString());
        completeSend("21", new CorrelationData.Confirm(true, null), false, false);
        completeSend("22", new CorrelationData.Confirm(true, null), false, false);

        publisher.publishPending();

        String token = claimedToken();
        verify(store).markPublished(first, token);
        verify(store).markPublished(second, token);
        verify(store).releaseClaim(11L, token);
    }

    @Test
    void interruptStopsTheRestOfTheBatch() {
        PendingOutbox first = new PendingOutbox(10L, 21L);
        PendingOutbox second = new PendingOutbox(11L, 22L);
        when(store.claimBatch(anyString(), eq(5)))
                .thenReturn(List.of(first, second));
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.EXCHANGE),
                eq(RabbitMqConfig.ROUTING_KEY),
                eq("21"),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));

        publisher.publishPending();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitMqConfig.EXCHANGE),
                eq(RabbitMqConfig.ROUTING_KEY),
                eq("22"),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));
        verify(store, never()).markPublished(any(), any());
        String token = claimedToken();
        verify(store).releaseClaim(10L, token);
        // The return leaves later claims held until claimed_until expires.
        verify(store, never()).releaseClaim(eq(11L), anyString());
    }

    @Test
    void noPendingEventsDoesNotPublish() {
        when(store.claimBatch(anyString(), eq(5))).thenReturn(List.of());

        publisher.publishPending();

        verifyNoInteractions(rabbitTemplate);
        verify(store, never()).markPublished(any(), any());
        verify(store, never()).releaseClaim(anyLong(), any());
    }

    private void completeSend(
            String body,
            CorrelationData.Confirm confirm,
            boolean returned,
            boolean checkMessage) {

        doAnswer(invocation -> {
            if (checkMessage) {
                MessagePostProcessor processor = invocation.getArgument(3);
                Message processed = processor.postProcessMessage(
                        new Message(body.getBytes(), new MessageProperties()));
                assertThat(processed.getMessageProperties().getDeliveryMode())
                        .isEqualTo(MessageDeliveryMode.PERSISTENT);
                assertThat(processed.getMessageProperties().getMessageId())
                        .isEqualTo(body);
            }
            CorrelationData correlation = invocation.getArgument(4);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        new Message(body.getBytes(), new MessageProperties()),
                        312,
                        "NO_ROUTE",
                        RabbitMqConfig.EXCHANGE,
                        RabbitMqConfig.ROUTING_KEY));
            }
            correlation.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.EXCHANGE),
                eq(RabbitMqConfig.ROUTING_KEY),
                eq(body),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    private String claimedToken() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(store).claimBatch(token.capture(), eq(5));
        return token.getValue();
    }
}
