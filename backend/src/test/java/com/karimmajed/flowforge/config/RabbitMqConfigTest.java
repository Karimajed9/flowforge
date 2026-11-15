package com.karimmajed.flowforge.config;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void executionQueueIsDurableAndHasNoDeadLetterExchange() {
        Queue queue = config.executionQueue();

        assertThat(queue.getName()).isEqualTo(RabbitMqConfig.QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    void executionQueueIsBoundForRequestedExecutions() {
        Binding binding = config.executionBinding(
                config.executionQueue(),
                config.executionExchange());

        assertThat(binding.getExchange()).isEqualTo(RabbitMqConfig.EXCHANGE);
        assertThat(binding.getDestination()).isEqualTo(RabbitMqConfig.QUEUE);
        assertThat(binding.getRoutingKey()).isEqualTo(RabbitMqConfig.ROUTING_KEY);
    }

    @Test
    void deadLetterQueueIsBoundForFailedExecutions() {
        DirectExchange exchange = config.deadLetterExchange();
        Queue queue = config.deadLetterQueue();
        Binding binding = config.deadLetterBinding(queue, exchange);

        assertThat(exchange.getName()).isEqualTo(RabbitMqConfig.DEAD_LETTER_EXCHANGE);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
        assertThat(queue.getName()).isEqualTo(RabbitMqConfig.DEAD_LETTER_QUEUE);
        assertThat(queue.isDurable()).isTrue();
        assertThat(binding.getExchange()).isEqualTo(RabbitMqConfig.DEAD_LETTER_EXCHANGE);
        assertThat(binding.getDestination()).isEqualTo(RabbitMqConfig.DEAD_LETTER_QUEUE);
        assertThat(binding.getRoutingKey()).isEqualTo(RabbitMqConfig.DEAD_LETTER_ROUTING_KEY);
    }
}
