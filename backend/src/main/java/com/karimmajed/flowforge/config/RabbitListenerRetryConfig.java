package com.karimmajed.flowforge.config;

import com.karimmajed.flowforge.exception.NonRetryableExecutionException;

import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitListenerRetryConfig {

    @Bean
    public RabbitListenerRetrySettingsCustomizer
            rabbitListenerRetrySettingsCustomizer() {

        return settings -> settings.getExceptionExcludes()
            .add(NonRetryableExecutionException.class);
    }
}