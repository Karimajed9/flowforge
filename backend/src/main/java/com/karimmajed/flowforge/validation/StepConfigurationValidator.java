package com.karimmajed.flowforge.validation;

import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.http.HttpJson;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StepConfigurationValidator {

    @FunctionalInterface
    public interface AddressLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final AddressLookup addressLookup;

    public StepConfigurationValidator() {
        this(InetAddress::getAllByName);
    }

    public StepConfigurationValidator(AddressLookup addressLookup) {
        this.addressLookup = addressLookup;
    }

    public void validate(
            StepType stepType,
            Map<String, Object> configuration) {

        if (configuration == null) {
            throw badRequest("Configuration is required");
        }

        switch (stepType) {
            case VALIDATE_FIELDS ->
                validateFields(configuration);

            case HTTP_REQUEST ->
                validateHttpRequest(configuration);

            case CONDITION ->
                validateCondition(configuration);
        }
    }

    private void validateFields(Map<String, Object> config) {

        if (!config.keySet().equals(Set.of("requiredFields"))) {
            throw badRequest(
                "VALIDATE_FIELDS requires only 'requiredFields'"
            );
        }

        Object fields = config.get("requiredFields");

        if (!(fields instanceof List<?> list) || list.isEmpty()) {
            throw badRequest(
                "requiredFields must be a non-empty array"
            );
        }

        for (Object field : list) {
            if (!(field instanceof String name) || name.isBlank()) {
                throw badRequest(
                    "Every required field must be a non-empty string"
                );
            }
        }
    }

    private void validateHttpRequest(Map<String, Object> config) {
        if (!config.containsKey("method")) {
            throw badRequest("HTTP_REQUEST requires 'method' and 'url'");
        }

        Object method = config.get("method");

        if (!(method instanceof String httpMethod)) {
            throw badRequest("Method must be GET or POST");
        }

        if ("GET".equals(httpMethod)) {
            if (!config.keySet().equals(Set.of("method", "url"))) {
                throw badRequest(
                    "HTTP_REQUEST requires 'method' and 'url'"
                );
            }
            validateHttpsUrl(config.get("url"));
            return;
        }

        if (!"POST".equals(httpMethod)) {
            throw badRequest("Method must be GET or POST");
        }

        if (!config.containsKey("url") || !config.containsKey("bodySource")) {
            throw badRequest(
                "HTTP POST requires 'method', 'url', and 'bodySource'"
            );
        }

        Object source = config.get("bodySource");

        if (!(source instanceof String bodySource)
                || (!"STATIC_JSON".equals(bodySource)
                        && !"WORKFLOW_PAYLOAD".equals(bodySource))) {
            throw badRequest(
                "HTTP body source must be STATIC_JSON or WORKFLOW_PAYLOAD"
            );
        }

        Set<String> expected = "STATIC_JSON".equals(bodySource)
                ? Set.of("method", "url", "bodySource", "body")
                : Set.of("method", "url", "bodySource");

        if (!config.keySet().equals(expected)) {
            throw badRequest("HTTP POST does not allow extra fields");
        }

        validateHttpsUrl(config.get("url"));

        if ("STATIC_JSON".equals(bodySource)) {
            try {
                HttpJson.bytes(config.get("body"));
            } catch (IllegalArgumentException ex) {
                throw badRequest(ex.getMessage());
            }
        }
    }

    private void validateHttpsUrl(Object url) {
        if (!(url instanceof String urlString)
                || urlString.length() > 2048) {
            throw badRequest("Invalid URL");
        }

        URI uri;

        try {
            uri = URI.create(urlString);
        } catch (IllegalArgumentException ex) {
            throw badRequest("Invalid URL");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535) {

            throw badRequest("A valid HTTPS URL is required");
        }

        validateHostname(uri.getHost());

        // Additional defense: check DNS resolution.
        validateResolvedAddresses(uri.getHost());
    }

    private void validateCondition(Map<String, Object> config) {
        Object operator = config.get("operator");
        if (!(operator instanceof String name)
                || !Set.of(
                        "EQUALS",
                        "NOT_EQUALS",
                        "GREATER_THAN",
                        "LESS_THAN",
                        "EXISTS").contains(name)) {
            throw badRequest("Condition operator is invalid");
        }

        boolean exists = "EXISTS".equals(name);
        if (exists && config.containsKey("value")) {
            throw badRequest("EXISTS does not take a value");
        }
        if (!exists && !config.containsKey("value")) {
            throw badRequest("Condition value is required");
        }
        Set<String> expected = exists
                ? Set.of("field", "operator", "trueSteps", "falseSteps")
                : Set.of("field", "operator", "value", "trueSteps", "falseSteps");
        if (!config.keySet().equals(expected)) {
            throw badRequest("CONDITION does not allow extra fields");
        }

        if (!isTopLevelField(config.get("field"))) {
            throw badRequest("Condition field must be a top-level name");
        }

        if (!exists) {
            validateComparisonValue(name, config.get("value"));
        }

        List<Long> trueSteps = branchIds(config.get("trueSteps"));
        List<Long> falseSteps = branchIds(config.get("falseSteps"));
        if (trueSteps.isEmpty() && falseSteps.isEmpty()) {
            throw badRequest("A condition needs at least one branch step");
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : trueSteps) {
            if (!seen.add(id)) {
                throw badRequest("A branch lists the same step twice");
            }
        }
        for (Long id : falseSteps) {
            if (!seen.add(id)) {
                throw badRequest("A branch lists the same step twice");
            }
        }
    }

    private void validateComparisonValue(String operator, Object value) {
        if ("GREATER_THAN".equals(operator) || "LESS_THAN".equals(operator)) {
            if (!isFiniteNumber(value)) {
                throw badRequest("Condition value must be a number");
            }
            return;
        }
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || isFiniteNumber(value)) {
            return;
        }
        throw badRequest("Condition value must be a string, number, boolean, or null");
    }

    private static boolean isFiniteNumber(Object value) {
        if (value instanceof BigDecimal) {
            return true;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return true;
        }
        if (value instanceof Float || value instanceof Double) {
            return Double.isFinite(((Number) value).doubleValue());
        }
        return false;
    }

    private List<Long> branchIds(Object value) {
        if (!(value instanceof List<?> list)) {
            throw badRequest("Branch steps must be arrays");
        }
        List<Long> ids = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Integer || item instanceof Long)) {
                throw badRequest("Branch steps must be positive integers");
            }
            long id = ((Number) item).longValue();
            if (id <= 0) {
                throw badRequest("Branch steps must be positive integers");
            }
            ids.add(id);
        }
        return ids;
    }

    private static boolean isTopLevelField(Object value) {
        if (!(value instanceof String name) || name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isWhitespace(character)
                    || character == '.'
                    || character == '['
                    || character == ']') {
                return false;
            }
        }
        return true;
    }

    private void validateHostname(String host) {

        String normalized = host.toLowerCase(Locale.ROOT);

        if (normalized.endsWith(".")) {
            normalized = normalized.substring(
                0, normalized.length() - 1
            );
        }

        if (normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.equals("local")
                || normalized.endsWith(".local")
                || normalized.equals("internal")
                || normalized.endsWith(".internal")) {

            throw badRequest(
                "Local or internal hostnames are not allowed"
            );
        }

        if (normalized.startsWith("[")
                || normalized.matches("[0-9.]+")
                || normalized.startsWith("0x")) {

            throw badRequest(
                "Direct IP addresses are not allowed"
            );
        }

        if (!normalized.contains(".")
                || normalized.startsWith(".")
                || normalized.endsWith(".")) {

            throw badRequest(
                "A valid public hostname is required"
            );
        }
    }

    private void validateResolvedAddresses(String host) {

        try {
            InetAddress[] addresses = addressLookup.resolve(host);

            if (addresses.length == 0) {
                throw badRequest(
                    "Hostname could not be resolved"
                );
            }

            for (InetAddress address : addresses) {

                if (isUnsafeAddress(address)) {
                    throw badRequest(
                        "URL resolves to a restricted IP address"
                    );
                }
            }

        } catch (UnknownHostException ex) {
            throw badRequest(
                "Hostname could not be resolved"
            );
        }
    }

    public static boolean isUnsafeAddress(InetAddress address) {

        // Reject standard non-public address categories.
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {

            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);

            // Additional special-use IPv4 ranges.
            return a == 0
                || a >= 224
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113);
        }

        if (bytes.length == 16) {

            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);

            // Only consider IPv6 global unicast space.
            if ((first & 0xE0) != 0x20) {
                return true;
            }

            // Reject the IPv6 documentation range.
            if (first == 0x20
                    && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0D
                    && Byte.toUnsignedInt(bytes[3]) == 0xB8) {
                return true;
            }

            return false;
        }

        return true;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }
}