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
import java.util.LinkedHashMap;
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
    void acceptsHttpsGet() {
        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", "https://example.com/hook")))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of(
                        "method", "GET",
                        "url", "https://example.com:8443/hook?x=1")))
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", "HTTPS://example.com/Callback")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsHttpsPostBodiesAndRejectsInvalidJson() {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("count", 2);
        body.put("ok", true);
        body.put("label", "ada");
        body.put("note", null);

        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of(
                        "method", "POST",
                        "url", "https://example.com/orders",
                        "bodySource", "STATIC_JSON",
                        "body", body)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                StepType.HTTP_REQUEST,
                Map.of(
                        "method", "POST",
                        "url", "https://example.com/orders",
                        "bodySource", "WORKFLOW_PAYLOAD")))
                .doesNotThrowAnyException();

        ResponseStatusException invalid = assertThrows(
                ResponseStatusException.class,
                () -> validator.validate(
                        StepType.HTTP_REQUEST,
                        Map.of(
                                "method", "POST",
                                "url", "https://example.com/orders",
                                "bodySource", "STATIC_JSON",
                                "body", new Object())));
        assertBadRequest(invalid, "HTTP body must be JSON");

        ResponseStatusException source = assertThrows(
                ResponseStatusException.class,
                () -> validator.validate(
                        StepType.HTTP_REQUEST,
                        Map.of(
                                "method", "POST",
                                "url", "https://example.com/orders",
                                "bodySource", "XML")));
        assertBadRequest(source, "HTTP body source must be STATIC_JSON or WORKFLOW_PAYLOAD");
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
                        "http method POST without a body source",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "POST", "url", "https://example.com"),
                        "HTTP POST requires 'method', 'url', and 'bodySource'"),
                args(
                        "http method PATCH",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "PATCH", "url", "https://example.com"),
                        "Method must be GET or POST"),
                args(
                        "http method DELETE",
                        StepType.HTTP_REQUEST,
                        Map.of("method", "DELETE", "url", "https://example.com"),
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
                        Map.of("method", "GET", "url", tooLong),
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
                        Map.of("method", "GET", "url", "https://example.com/hook#section"),
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
                        Map.of("method", "GET", "url", "not-a-url"),
                        "A valid HTTPS URL is required"),
                args(
                        "unknown condition operator",
                        StepType.CONDITION,
                        condition("NOPE", 1, List.of(1), List.of()),
                        "Condition operator is invalid"),
                args(
                        "condition extra field",
                        StepType.CONDITION,
                        extraCondition(),
                        "CONDITION does not allow extra fields"),
                args(
                        "equals without a value",
                        StepType.CONDITION,
                        Map.of(
                                "field", "amount",
                                "operator", "EQUALS",
                                "trueSteps", List.of(1),
                                "falseSteps", List.of()),
                        "Condition value is required"),
                args(
                        "exists with a value",
                        StepType.CONDITION,
                        Map.of(
                                "field", "amount",
                                "operator", "EXISTS",
                                "value", 1,
                                "trueSteps", List.of(1),
                                "falseSteps", List.of()),
                        "EXISTS does not take a value"),
                args(
                        "greater than with a numeric string",
                        StepType.CONDITION,
                        condition("GREATER_THAN", "10", List.of(1), List.of()),
                        "Condition value must be a number"),
                args(
                        "blank condition field",
                        StepType.CONDITION,
                        condition("EQUALS", 1, List.of(1), List.of(), " "),
                        "Condition field must be a top-level name"),
                args(
                        "dotted condition field",
                        StepType.CONDITION,
                        condition("EQUALS", 1, List.of(1), List.of(), "order.amount"),
                        "Condition field must be a top-level name"),
                args(
                        "condition with no branch steps",
                        StepType.CONDITION,
                        condition("EQUALS", 1, List.of(), List.of()),
                        "A condition needs at least one branch step")
        );
    }

    @Test
    void acceptsEqualsAndExistsConditions() {
        assertThatCode(() -> validator.validate(
                StepType.CONDITION,
                condition("EQUALS", 1, List.of(2), List.of(3))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                StepType.CONDITION,
                Map.of(
                        "field", "amount",
                        "operator", "EXISTS",
                        "trueSteps", List.of(),
                        "falseSteps", List.of(3))))
                .doesNotThrowAnyException();
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

    private static Map<String, Object> condition(
            String operator,
            Object value,
            List<Integer> trueSteps,
            List<Integer> falseSteps) {
        return condition(operator, value, trueSteps, falseSteps, "amount");
    }

    private static Map<String, Object> condition(
            String operator,
            Object value,
            List<Integer> trueSteps,
            List<Integer> falseSteps,
            String field) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("field", field);
        configuration.put("operator", operator);
        configuration.put("value", value);
        configuration.put("trueSteps", trueSteps);
        configuration.put("falseSteps", falseSteps);
        return configuration;
    }

    private static Map<String, Object> extraCondition() {
        Map<String, Object> configuration = condition("EQUALS", 1, List.of(1), List.of());
        configuration.put("note", "nope");
        return configuration;
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
