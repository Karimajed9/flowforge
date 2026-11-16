package com.karimmajed.flowforge.config;

public final class DeadLetterPolicyVerifier {

    private DeadLetterPolicyVerifier() {
    }

    public static void verify(BrokerDeadLetterView view) {
        if (view == null
                || !"flowforge-dlx".equals(view.policyName())
                || !RabbitMqConfig.DEAD_LETTER_EXCHANGE.equals(view.deadLetterExchange())
                || !RabbitMqConfig.DEAD_LETTER_ROUTING_KEY.equals(view.deadLetterRoutingKey())
                || !view.deadLetterBindingPresent()) {

            throw new IllegalStateException(
                    "Dead-letter policy flowforge-dlx is not effective on "
                            + RabbitMqConfig.QUEUE);
        }
    }
}
