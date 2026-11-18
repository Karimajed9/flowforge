package com.karimmajed.flowforge.validation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.net.InetAddress;

@TestConfiguration
public class FixedAddressValidator {

    @Bean
    StepConfigurationValidator stepConfigurationValidator() throws Exception {
        InetAddress address = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        return new StepConfigurationValidator(host -> new InetAddress[] {address});
    }
}
