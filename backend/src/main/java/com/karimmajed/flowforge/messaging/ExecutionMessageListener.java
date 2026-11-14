package com.karimmajed.flowforge.messaging;

import com.karimmajed.flowforge.config.RabbitMqConfig;
import com.karimmajed.flowforge.service.WorkflowExecutionProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ExecutionMessageListener {

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
        queues = RabbitMqConfig.QUEUE,
        autoStartup = "${flowforge.consumer.enabled:false}"
    )
    public void receive(String messageBody) {

        Long executionId;

        try {
            executionId = Long.parseLong(messageBody.trim());

            if (executionId <= 0) {
                throw new NumberFormatException(
                    "Execution ID must be positive"
                );
            }

        } catch (NumberFormatException ex) {
            throw new AmqpRejectAndDontRequeueException(
                "Invalid execution ID: " + messageBody,
                ex
            );
        }

        log.info(
            "RabbitMQ received execution {}",
            executionId
        );

        processor.process(executionId);
    }
}