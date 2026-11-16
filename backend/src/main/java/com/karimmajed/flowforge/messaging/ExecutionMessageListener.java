package com.karimmajed.flowforge.messaging;

import com.karimmajed.flowforge.config.RabbitMqConfig;
import com.karimmajed.flowforge.exception.NonRetryableExecutionException;
import com.karimmajed.flowforge.service.WorkflowExecutionProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMessageListener {

    public static final String LISTENER_ID = "executionConsumer";

    private static final Logger log =
        LoggerFactory.getLogger(
            ExecutionMessageListener.class
        );

    private final WorkflowExecutionProcessor processor;

    public ExecutionMessageListener(
            WorkflowExecutionProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(
        id = LISTENER_ID,
        queues = RabbitMqConfig.QUEUE,
        autoStartup = "false"
    )
    public void receive(String messageBody) {

        Long executionId;

        try {
            executionId = Long.parseLong(
                messageBody.trim()
            );

            if (executionId <= 0) {
                throw new NumberFormatException(
                    "Execution ID must be positive"
                );
            }

        } catch (NumberFormatException | NullPointerException ex) {

            log.warn("Invalid execution message received");

            throw new NonRetryableExecutionException(
                "Invalid execution ID in RabbitMQ message",
                ex
            );
        }

        log.info(
            "Received execution {} from RabbitMQ",
            executionId
        );

        processor.process(executionId);
    }
}