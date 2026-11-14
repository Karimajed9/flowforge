package com.karimmajed.flowforge.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class StepConfigurationValidatorAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1",
        "10.0.0.1",
        "192.168.1.1",
        "169.254.169.254",
        "100.64.0.1",
        "198.18.0.1",
        "::1",
        "fc00::1",
        "fe80::1"
    })
    void rejectsRestrictedAddresses(String ip) throws Exception {

        InetAddress address = InetAddress.getByName(ip);

        assertThat(
            StepConfigurationValidator.isUnsafeAddress(address)
        ).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8",
        "1.1.1.1",
        "2606:4700:4700::1111"
    })
    void acceptsExamplesOfPublicAddresses(String ip)
            throws Exception {

        InetAddress address = InetAddress.getByName(ip);

        assertThat(
            StepConfigurationValidator.isUnsafeAddress(address)
        ).isFalse();
    }
}