package com.karimmajed.flowforge.config;

import com.karimmajed.flowforge.exception.NonRetryableExecutionException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.retry.RetryPolicySettings;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitListenerRetryConfigTest {

    private final RabbitListenerRetryConfig config =
        new RabbitListenerRetryConfig();

    @Test
    void permanentErrorsAreNotRetried() {

        RetryPolicySettings settings =
            new RetryPolicySettings();

        config.rabbitListenerRetrySettingsCustomizer()
            .customize(settings);

        var policy = settings.createRetryPolicy();

        assertThat(policy.shouldRetry(
            new NonRetryableExecutionException(
                "Execution not found"
            )
        )).isFalse();
    }

    @Test
    void wrappedPermanentErrorsAreNotRetried() {

        RetryPolicySettings settings =
            new RetryPolicySettings();

        config.rabbitListenerRetrySettingsCustomizer()
            .customize(settings);

        var policy = settings.createRetryPolicy();

        RuntimeException wrapped = new RuntimeException(
            "Listener processing failed",
            new NonRetryableExecutionException(
                "Invalid message"
            )
        );

        assertThat(policy.shouldRetry(wrapped))
            .isFalse();
    }

    @Test
    void unexpectedErrorsRemainRetryable() {

        RetryPolicySettings settings =
            new RetryPolicySettings();

        config.rabbitListenerRetrySettingsCustomizer()
            .customize(settings);

        var policy = settings.createRetryPolicy();

        assertThat(policy.shouldRetry(
            new IllegalStateException(
                "Temporary processing failure"
            )
        )).isTrue();
    }
}