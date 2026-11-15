package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.config.RabbitMqConfig;
import com.karimmajed.flowforge.repository.OutboxDeliveryStore;
import com.karimmajed.flowforge.repository.OutboxDeliveryStore.PendingOutbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ExecutionOutboxPublisher {

    private static final Logger log =
        LoggerFactory.getLogger(
            ExecutionOutboxPublisher.class
        );

    private final OutboxDeliveryStore store;
    private final RabbitTemplate rabbitTemplate;

    public ExecutionOutboxPublisher(
            OutboxDeliveryStore store,
            RabbitTemplate rabbitTemplate) {

        this.store = store;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(
        fixedDelayString =
            "${flowforge.outbox.poll-interval-ms:2000}"
    )
    public void publishPending() {

        String claimToken =
            UUID.randomUUID().toString();

        // Short DB transaction: claim rows and commit.
        List<PendingOutbox> events =
            store.claimBatch(claimToken, 5);

        // No DB transaction is open during this loop.
        for (PendingOutbox event : events) {

            try {
                CorrelationData correlation =
                    new CorrelationData(
                        UUID.randomUUID().toString()
                    );

                rabbitTemplate.convertAndSend(
                    RabbitMqConfig.EXCHANGE,
                    RabbitMqConfig.ROUTING_KEY,
                    Long.toString(event.executionId()),
                    message -> {
                        message.getMessageProperties()
                            .setDeliveryMode(
                                MessageDeliveryMode.PERSISTENT
                            );

                        message.getMessageProperties()
                            .setMessageId(
                                Long.toString(event.executionId())
                            );

                        return message;
                    },
                    correlation
                );

                var confirmation = correlation
                    .getFuture()
                    .get(5, TimeUnit.SECONDS);

                if (!confirmation.ack()) {
                    log.warn(
                        "RabbitMQ did not confirm execution {}",
                        event.executionId()
                    );
                    continue;
                }

                if (correlation.getReturned() != null) {
                    log.warn(
                        "RabbitMQ could not route execution {}",
                        event.executionId()
                    );
                    continue;
                }

                // Short DB transaction: record success.
                boolean marked =
                    store.markPublished(event, claimToken);

                if (marked) {
                    log.info(
                        "Published execution {}",
                        event.executionId()
                    );
                } else {
                    log.warn(
                        "Publication claim lost for execution {}",
                        event.executionId()
                    );
                }

            } catch (InterruptedException ex) {

                Thread.currentThread().interrupt();

                log.warn(
                    "Publisher interrupted while processing {}",
                    event.executionId()
                );

                return;

            } catch (Exception ex) {

                log.warn(
                    "Publishing failed for execution {}: {}",
                    event.executionId(),
                    ex.getMessage()
                );

            } finally {

                try {
                    // Releases unsuccessful claims.
                    // Successful rows are already marked published.
                    store.releaseClaim(
                        event.outboxId(),
                        claimToken
                    );

                } catch (Exception ex) {
                    log.warn(
                        "Could not release outbox claim {}",
                        event.outboxId(),
                        ex
                    );
                }
            }
        }
    }
}