package com.karimmajed.flowforge.config;

public record BrokerDeadLetterView(
        String policyName,
        String deadLetterExchange,
        String deadLetterRoutingKey,
        boolean deadLetterBindingPresent
) {}
