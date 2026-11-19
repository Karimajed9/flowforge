package com.karimmajed.flowforge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    static boolean taken(Map<String, Object> configuration, Map<String, Object> payload) {
        if (configuration == null) {
            throw invalid("Condition configuration is invalid");
        }
        String field = field(configuration.get("field"));
        String operator = operator(configuration.get("operator"));
        Map<String, Object> body = payload == null ? Map.of() : payload;

        if ("EXISTS".equals(operator)) {
            return body.containsKey(field);
        }

        if (!body.containsKey(field)) {
            throw invalid("Condition field is missing: " + field);
        }

        Object actual = body.get(field);
        Object expected = configuration.get("value");
        Kind actualKind = kind(actual);
        Kind expectedKind = kind(expected);

        if (actualKind == Kind.OTHER || expectedKind == Kind.OTHER) {
            throw invalid(
                "Condition '%s': unsupported type (expected %s, received %s)."
                    .formatted(field, expectedKind, actualKind)
            );
        }

        if ("GREATER_THAN".equals(operator) || "LESS_THAN".equals(operator)) {
            if (actualKind != Kind.NUMBER || expectedKind != Kind.NUMBER) {
                throw invalid(
                    "Condition '%s': operator %s requires numbers (expected %s, received %s)."
                        .formatted(field, operator, expectedKind, actualKind)
                );
            }
        
            int compared = decimal(actual).compareTo(decimal(expected));
        
            return "GREATER_THAN".equals(operator)
                ? compared > 0
                : compared < 0;
        }

        if (actualKind != expectedKind) {
            throw invalid(
                "Condition '%s': expected %s, but received %s."
                    .formatted(field, expectedKind, actualKind)
            );
        }

        boolean equal = switch (actualKind) {
            case STRING -> ((String) actual).equals(expected);
            case BOOLEAN -> actual.equals(expected);
            case NUMBER -> decimal(actual).compareTo(decimal(expected)) == 0;
            case NULL -> true;
            case OTHER -> false;
        };
        return "NOT_EQUALS".equals(operator) ? !equal : equal;
    }

    static List<Long> skippedIds(Map<String, Object> configuration, boolean taken) {
        String untaken = taken ? "falseSteps" : "trueSteps";
        Object value = configuration.get(untaken);
        if (!(value instanceof List<?> list)) {
            throw invalid("Condition configuration is invalid");
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            Long id = integralId(item);
            if (id == null) {
                throw invalid("Condition configuration is invalid");
            }
            ids.add(id);
        }
        return ids;
    }

    private static String field(Object value) {
        if (!(value instanceof String name) || !isTopLevelName(name)) {
            throw invalid("Condition configuration is invalid");
        }
        return name;
    }

    private static String operator(Object value) {
        if (!(value instanceof String name)) {
            throw invalid("Condition configuration is invalid");
        }
        return name.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isTopLevelName(String name) {
        if (name.isBlank()) {
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

    private static Kind kind(Object value) {
        if (value == null) {
            return Kind.NULL;
        }
        if (value instanceof String) {
            return Kind.STRING;
        }
        if (value instanceof Boolean) {
            return Kind.BOOLEAN;
        }
        if (decimalOrNull(value) != null) {
            return Kind.NUMBER;
        }
        return Kind.OTHER;
    }

    private static BigDecimal decimal(Object value) {
        BigDecimal decimal = decimalOrNull(value);
        if (decimal == null) {
            throw invalid("Condition field has an incompatible type");
        }
        return decimal;
    }

    private static BigDecimal decimalOrNull(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                return null;
            }
            return BigDecimal.valueOf(number);
        }
        return null;
    }

    static Long integralId(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) {
            return null;
        }
        long id = ((Number) value).longValue();
        return id > 0 ? id : null;
    }

    private static InvalidCondition invalid(String message) {
        return new InvalidCondition(message);
    }

    private enum Kind {
        STRING,
        NUMBER,
        BOOLEAN,
        NULL,
        OTHER
    }

    static final class InvalidCondition extends RuntimeException {

        InvalidCondition(String message) {
            super(message);
        }
    }
}
