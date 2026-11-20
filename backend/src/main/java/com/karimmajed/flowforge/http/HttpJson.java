package com.karimmajed.flowforge.http;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Writes a JSON value without changing its JSON type.
 * The encoded bytes are the request body. They are not logged.
 */
public final class HttpJson {

    public static final int MAX_BYTES = 65_536;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpJson() {
    }

    public static byte[] bytes(Object value) {
        if (!isJson(value)) {
            throw new IllegalArgumentException("HTTP body must be JSON");
        }

        byte[] encoded;

        try {
            encoded = JSON.writeValueAsBytes(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("HTTP body must be JSON");
        }

        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("HTTP request body too large");
        }

        return encoded;
    }

    public static String text(byte[] encoded) {
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static boolean isJson(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return true;
        }

        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger) {
            return true;
        }

        if (value instanceof Float || value instanceof Double) {
            return Double.isFinite(((Number) value).doubleValue());
        }

        if (value instanceof BigDecimal) {
            return true;
        }

        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!isJson(item)) {
                    return false;
                }
            }
            return true;
        }

        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || !isJson(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }
}
