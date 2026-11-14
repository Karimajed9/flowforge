package com.karimmajed.flowforge.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE =
        "flowforge.executions";

    public static final String QUEUE =
        "flowforge.executions.pending";

    public static final String ROUTING_KEY =
        "execution.requested";

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
            Queue executionQueue,
            DirectExchange executionExchange) {

        return BindingBuilder
            .bind(executionQueue)
            .to(executionExchange)
            .with(ROUTING_KEY);
    }
}