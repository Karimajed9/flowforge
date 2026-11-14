package com.karimmajed.flowforge.validation;

import com.karimmajed.flowforge.enums.StepType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepConfigurationValidatorSecurityTest {

    private final StepConfigurationValidator validator =
        resolvingTo("8.8.8.8");

    @ParameterizedTest
    @ValueSource(strings = {
        "https://localhost",
        "https://localhost.",
        "https://api.localhost",
        "https://127.0.0.1",
        "https://127.0.0.1:8443",
        "https://10.0.0.1",
        "https://192.168.1.10",
        "https://169.254.169.254",
        "https://2130706433",
        "https://[::1]",
        "https://[fe80::1]",
        "https://[::ffff:127.0.0.1]",
        "https://server.local",
        "https://metadata.google.internal"
    })
    void rejectsUnsafeUrl(String url) {

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", url)
            )
        );

        assertThat(exception.getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({
        "https://127.0.0.1.nip.io, 127.0.0.1",
        "https://10.0.0.1.nip.io, 10.0.0.1",
        "https://169.254.169.254.nip.io, 169.254.169.254"
    })
    void rejectsHostnameThatResolvesToRestrictedAddress(
            String url,
            String resolvedIp) throws Exception {

        String host = url.substring("https://".length());
        StepConfigurationValidator resolving =
            new StepConfigurationValidator(requested -> {
                assertThat(requested).isEqualTo(host);
                return new InetAddress[] {
                    InetAddress.getByName(resolvedIp)
                };
            });

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> resolving.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", url)
            )
        );

        assertThat(exception.getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getReason())
            .isEqualTo("URL resolves to a restricted IP address");
    }

    @Test
    void acceptsPublicHttpsHostname() {

        assertThatCode(() -> validator.validate(
            StepType.HTTP_REQUEST,
            Map.of(
                "method", "GET",
                "url", "https://api.example.com/webhook"
            )
        )).doesNotThrowAnyException();
    }

    private static StepConfigurationValidator resolvingTo(String ip) {
        return new StepConfigurationValidator(host ->
            new InetAddress[] { InetAddress.getByName(ip) });
    }
}