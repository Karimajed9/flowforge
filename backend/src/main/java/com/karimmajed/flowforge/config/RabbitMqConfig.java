package com.karimmajed.flowforge.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    // Existing execution queue configuration.
    public static final String EXCHANGE =
        "flowforge.executions";

    public static final String QUEUE =
        "flowforge.executions.pending";

    public static final String ROUTING_KEY =
        "execution.requested";

    // New dead-letter configuration.
    public static final String DEAD_LETTER_EXCHANGE =
        "flowforge.executions.dlx";

    public static final String DEAD_LETTER_QUEUE =
        "flowforge.executions.dead";

    public static final String DEAD_LETTER_ROUTING_KEY =
        "execution.failed";

    @Bean
    public DirectExchange executionExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue executionQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding executionBinding(
            @Qualifier("executionQueue")
            Queue queue,

            @Qualifier("executionExchange")
            DirectExchange exchange) {

        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(ROUTING_KEY);
    }

    // New dead-letter exchange.
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(
            DEAD_LETTER_EXCHANGE,
            true,
            false
        );
    }

    // New dead-letter queue.
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder
            .durable(DEAD_LETTER_QUEUE)
            .build();
    }

    // Route rejected messages into the DLQ.
    @Bean
    public Binding deadLetterBinding(
            @Qualifier("deadLetterQueue")
            Queue queue,

            @Qualifier("deadLetterExchange")
            DirectExchange exchange) {

        return BindingBuilder
            .bind(queue)
            .to(exchange)
            .with(DEAD_LETTER_ROUTING_KEY);
    }
}