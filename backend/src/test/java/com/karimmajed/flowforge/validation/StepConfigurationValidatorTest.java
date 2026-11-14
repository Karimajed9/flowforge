package com.karimmajed.flowforge.validation;

import com.karimmajed.flowforge.enums.StepType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepConfigurationValidatorTest {

    private final StepConfigurationValidator validator =
            new StepConfigurationValidator(host -> new InetAddress[] {
                    InetAddress.getByName("8.8.8.8")
            });

    @Test
    void acceptsValidateFields() {
        assertThatCode(() -> validator.validate(
                StepType.VALIDATE_FIELDS,
                Map.of("requiredFields", List.of("email"))))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(
                StepType.VALIDATE_FIELDS,
                Map.of("requiredFields", List.of("email", "name"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsHttpGetAndPost() {
        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", "https://example.com/hook")))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of(
                        "method", "POST",
                        "url", "https://example.com:8443/hook?x=1")))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", "HTTPS://example.com/Callback")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsUrlOf2048Characters() {
        String prefix = "https://example.com/";
        String url = prefix + "a".repeat(2048 - prefix.length());

        assertThat(url).hasSize(2048);
        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", url)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(StepType.class)
    void rejectsNullConfiguration(StepType stepType) {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> validator.validate(stepType, null));

        assertBadRequest(ex, "Configuration is required");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void rejectsInvalidConfiguration(
            String name,
            StepType stepType,
            Map<String, Object> configuration,
            String message) {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> validator.validate(stepType, configuration),
                name);

        assertThat(ex.getStatusCode()).as(name).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).as(name).isEqualTo(message);
    }

    private static Stream<Arguments> invalidConfigurations() {
        String prefix = "https://example.com/";
        String tooLong = prefix + "a".repeat(2049 - prefix.length());

        return Stream.of(
                args(
                        "validate fields with no keys",
                        StepType.VALIDATE_FIELDS,
                        Map.of(),
                        "VALIDATE_FIELDS requires only 'requiredFields'"),
                args(
                        "validate fields with the wrong key",
                        StepType.VALIDATE_FIELDS,
                        Map.of("fields", List.of("email")),
                        "VALIDATE_FIELDS requires only 'requiredFields'"),
                args(
                        "validate fields with an extra key",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", List.of("email"), "extra", true),
                        "VALIDATE_FIELDS requires only 'requiredFields'"),
                args(
                        "requiredFields is a string",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", "email"),
                        "requiredFields must be a non-empty array"),
                args(
                        "requiredFields is empty",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", List.of()),
                        "requiredFields must be a non-empty array"),
                args(
                        "requiredFields is null",
                        StepType.VALIDATE_FIELDS,
                        map("requiredFields", null),
                        "requiredFields must be a non-empty array"),
                args(
                        "required field is blank",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", List.of("  ")),
                        "Every required field must be a non-empty string"),
                args(
                        "required field is empty",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", List.of("")),
                        "Every required field must be a non-empty string"),
                args(
                        "required field is not a string",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", List.of("email", 1)),
                        "Every required field must be a non-empty string"),
                args(
                        "required field is null",
                        StepType.VALIDATE_FIELDS,
                        Map.of("requiredFields", nullableList("email", null)),
                        "Every required field must be a non-empty string"),
                args(
                        "http request with no keys",
                        StepType.HTTP_REQUEST,
                        Map.of(),
                        "HTTP_REQUEST requires 'method' and 'url'"),
                args(
                        "http request missing url",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET"),
                        "HTTP_REQUEST requires 'method' and 'url'"),
                args(
                        "http request with an extra key",
                        StepType.HTTP_REQUEST,
                        Map.of(
                                "method", "GET",
                                "url", "https://example.com",
                                "headers", Map.of()),
                        "HTTP_REQUEST requires 'method' and 'url'"),
                args(
                        "http method PUT",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "PUT", "url", "https://example.com"),
                        "Method must be GET or POST"),
                args(
                        "http method lowercase",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "get", "url", "https://example.com"),
                        "Method must be GET or POST"),
                args(
                        "http method is not a string",
                        StepType.HTTP_REQUEST,
                        Map.of("method", 1, "url", "https://example.com"),
                        "Method must be GET or POST"),
                args(
                        "url is not a string",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET", "url", 1),
                        "Invalid URL"),
                args(
                        "url longer than 2048 characters",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "POST", "url", tooLong),
                        "Invalid URL"),
                args(
                        "url with a space",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET", "url", "https://example.com/a b"),
                        "Invalid URL"),
                args(
                        "url is http",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET", "url", "http://example.com/hook"),
                        "A valid HTTPS URL is required"),
                args(
                        "url has a fragment",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "POST", "url", "https://example.com/hook#section"),
                        "A valid HTTPS URL is required"),
                args(
                        "url has user info",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET", "url", "https://api:secret@example.com/hook"),
                        "A valid HTTPS URL is required"),
                args(
                        "url has an empty authority",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET", "url", "https://"),
                        "Invalid URL"),
                args(
                        "url has no host",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "GET", "url", "https:opaque"),
                        "A valid HTTPS URL is required"),
                args(
                        "url is not an http address",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "POST", "url", "not-a-url"),
                        "A valid HTTPS URL is required")
        );
    }

    private static void assertBadRequest(ResponseStatusException ex, String message) {
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo(message);
    }

    private static Arguments args(
            String name,
            StepType stepType,
            Map<String, Object> configuration,
            String message) {
        return Arguments.of(name, stepType, configuration, message);
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(key, value);
        return configuration;
    }

    private static List<Object> nullableList(Object... values) {
        List<Object> fields = new ArrayList<>();
        for (Object value : values) {
            fields.add(value);
        }
        return fields;
    }
}
