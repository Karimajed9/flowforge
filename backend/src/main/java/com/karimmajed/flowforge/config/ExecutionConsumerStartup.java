package com.karimmajed.flowforge.config;

import com.karimmajed.flowforge.messaging.ExecutionMessageListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ExecutionConsumerStartup implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ExecutionConsumerStartup.class);

    private final boolean consumerEnabled;

    private final Duration wait;

    private final Duration interval;

    private final DeadLetterPolicySource policies;

    private final RabbitListenerEndpointRegistry registry;

    private final Sleeper sleeper;

    private final Runnable declareTopology;

    @Autowired
    public ExecutionConsumerStartup(
            @Value("${flowforge.consumer.enabled:false}") boolean consumerEnabled,
            @Value("${flowforge.rabbitmq.policy-wait:60s}") Duration wait,
            @Value("${flowforge.rabbitmq.policy-interval:2s}") Duration interval,
            DeadLetterPolicySource policies,
            RabbitListenerEndpointRegistry registry,
            AmqpAdmin admin) {

        this(
                consumerEnabled,
                wait,
                interval,
                policies,
                registry,
                millis -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Interrupted while waiting for the dead-letter policy",
                                ex);
                    }
                },
                () -> declareBrokerTopology(admin));
    }

    ExecutionConsumerStartup(
            boolean consumerEnabled,
            Duration wait,
            Duration interval,
            DeadLetterPolicySource policies,
            RabbitListenerEndpointRegistry registry,
            Sleeper sleeper) {

        this(
                consumerEnabled,
                wait,
                interval,
                policies,
                registry,
                sleeper,
                () -> {
                });
    }

    private ExecutionConsumerStartup(
            boolean consumerEnabled,
            Duration wait,
            Duration interval,
            DeadLetterPolicySource policies,
            RabbitListenerEndpointRegistry registry,
            Sleeper sleeper,
            Runnable declareTopology) {

        this.consumerEnabled = consumerEnabled;
        this.wait = wait;
        this.interval = interval;
        this.policies = policies;
        this.registry = registry;
        this.sleeper = sleeper;
        this.declareTopology = declareTopology;
    }

    private static void declareBrokerTopology(AmqpAdmin admin) {
        if (admin instanceof RabbitAdmin rabbitAdmin) {
            rabbitAdmin.initialize();
            return;
        }
        throw new IllegalStateException(
                "RabbitAdmin is required before checking the dead-letter policy");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!consumerEnabled) {
            return;
        }

        Instant deadline = Instant.now().plus(wait);
        IllegalStateException lastFailure = null;

        while (true) {
            try {
                declareTopology.run();
                DeadLetterPolicyVerifier.verify(policies.read());
                startConsumer();
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex instanceof IllegalStateException illegal
                        ? illegal
                        : new IllegalStateException(
                                "Dead-letter policy flowforge-dlx is not effective on "
                                        + RabbitMqConfig.QUEUE,
                                ex);

                if (!Instant.now().isBefore(deadline)) {
                    throw new IllegalStateException(
                            "Refusing to consume from " + RabbitMqConfig.QUEUE
                                    + " because policy flowforge-dlx is missing or wrong",
                            lastFailure);
                }

                log.warn(
                        "Waiting for dead-letter policy flowforge-dlx before consuming");
                sleeper.sleep(interval.toMillis());
            }
        }
    }

    private void startConsumer() {
        MessageListenerContainer container = registry.getListenerContainer(
                ExecutionMessageListener.LISTENER_ID);

        if (container == null) {
            throw new IllegalStateException(
                    "Execution listener is not registered");
        }

        container.start();
    }

    interface Sleeper {
        void sleep(long millis);
    }
}
