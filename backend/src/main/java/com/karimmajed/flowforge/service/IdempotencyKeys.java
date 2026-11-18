package com.karimmajed.flowforge.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class IdempotencyKeys {

    public static final String PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private static final Pattern VALID = Pattern.compile(PATTERN);

    private IdempotencyKeys() {
    }

    public static String resolve(long workflowId, String clientKey, byte[] body) {
        if (clientKey == null || clientKey.isBlank()) {
            return derived(workflowId, body);
        }
        if (!VALID.matcher(clientKey).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A valid Idempotency-Key is required");
        }
        return clientKey;
    }

    static String derived(long workflowId, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(workflowId).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            return "run." + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
