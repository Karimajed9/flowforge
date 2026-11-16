package com.karimmajed.flowforge.config;

public interface DeadLetterPolicySource {

    BrokerDeadLetterView read();
}
