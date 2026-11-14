package com.karimmajed.flowforge.messaging;

import com.karimmajed.flowforge.service.WorkflowExecutionProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExecutionMessageListenerTest {

    @Mock
    private WorkflowExecutionProcessor processor;

    @InjectMocks
    private ExecutionMessageListener listener;

    @Test
    void numericBodyStartsProcessing() {
        listener.receive(" 21 ");

        verify(processor).process(21L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "-1", "abc", "21.5"})
    void invalidBodyIsRejectedWithoutProcessing(String body) {
        AmqpRejectAndDontRequeueException ex = assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> listener.receive(body));

        assertThat(ex.getMessage()).contains("Invalid execution ID: " + body);
        verify(processor, never()).process(any());
    }
}
