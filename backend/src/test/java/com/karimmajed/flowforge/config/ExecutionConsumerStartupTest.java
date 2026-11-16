package com.karimmajed.flowforge.config;

import com.karimmajed.flowforge.messaging.ExecutionMessageListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionConsumerStartupTest {

    @Mock
    private DeadLetterPolicySource policies;

    @Mock
    private RabbitListenerEndpointRegistry registry;

    @Mock
    private MessageListenerContainer container;

    @Test
    void missingPolicyRefusesToStartTheConsumer() {
        when(policies.read()).thenThrow(new IllegalStateException("management unavailable"));
        ExecutionConsumerStartup startup = startup(true, Duration.ZERO);

        assertThatThrownBy(() -> startup.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to consume");

        verify(registry, never()).getListenerContainer(ExecutionMessageListener.LISTENER_ID);
    }

    @Test
    void wrongPolicyRefusesToStartTheConsumer() {
        when(policies.read()).thenReturn(new BrokerDeadLetterView(
                "flowforge-dlx",
                "other.exchange",
                RabbitMqConfig.DEAD_LETTER_ROUTING_KEY,
                true));
        ExecutionConsumerStartup startup = startup(true, Duration.ZERO);

        assertThatThrownBy(() -> startup.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to consume");

        verify(container, never()).start();
    }

    @Test
    void effectivePolicyStartsTheConsumer() {
        when(policies.read()).thenReturn(effective());
        when(registry.getListenerContainer(ExecutionMessageListener.LISTENER_ID))
                .thenReturn(container);

        startup(true, Duration.ofSeconds(5)).run(null);

        verify(container).start();
    }

    @Test
    void disabledConsumerDoesNotReadThePolicy() {
        startup(false, Duration.ZERO).run(null);

        verifyNoInteractions(policies, registry);
    }

    @Test
    void managementPathEncodesTheDefaultVhostOnce() {
        URI queue = RabbitManagementDeadLetterClient.queueUri(
                "http://localhost:15672",
                RabbitMqConfig.QUEUE);
        URI binding = RabbitManagementDeadLetterClient.bindingUri(
                "http://localhost:15672",
                RabbitMqConfig.DEAD_LETTER_EXCHANGE,
                RabbitMqConfig.DEAD_LETTER_QUEUE);

        assertThat(queue.getRawPath())
                .isEqualTo("/api/queues/%2F/flowforge.executions.pending");
        assertThat(binding.getRawPath())
                .isEqualTo("/api/bindings/%2F/e/flowforge.executions.dlx/q/flowforge.executions.dead");
        assertThat(queue.getRawPath()).doesNotContain("%252F");
        assertThat(binding.getRawPath()).doesNotContain("%252F");
    }

    @Test
    void parsedManagementPayloadMustMatchThePolicyAndBinding() {
        BrokerDeadLetterView missing = RabbitManagementDeadLetterClient.parse(
                "{\"policy\":null,\"effective_policy_definition\":{}}",
                "[]");

        assertThatThrownBy(() -> DeadLetterPolicyVerifier.verify(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowforge-dlx");

        BrokerDeadLetterView effective = RabbitManagementDeadLetterClient.parse("""
                {
                  "policy": "flowforge-dlx",
                  "effective_policy_definition": {
                    "dead-letter-exchange": "flowforge.executions.dlx",
                    "dead-letter-routing-key": "execution.failed"
                  }
                }
                """,
                """
                [{"routing_key":"execution.failed"}]
                """);

        DeadLetterPolicyVerifier.verify(effective);
    }

    private ExecutionConsumerStartup startup(boolean enabled, Duration wait) {
        return new ExecutionConsumerStartup(
                enabled,
                wait,
                Duration.ZERO,
                policies,
                registry,
                millis -> {
                });
    }

    private static BrokerDeadLetterView effective() {
        return new BrokerDeadLetterView(
                "flowforge-dlx",
                RabbitMqConfig.DEAD_LETTER_EXCHANGE,
                RabbitMqConfig.DEAD_LETTER_ROUTING_KEY,
                true);
    }
}
