package com.karimmajed.flowforge.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {

    @Test
    void omittedKeyIsStableForTheSameSubmission() {
        byte[] body = "{\"amount\":1}".getBytes(StandardCharsets.UTF_8);
        String first = IdempotencyKeys.resolve(4L, null, body);
        String second = IdempotencyKeys.resolve(4L, "  ", body);

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("run.");
        assertThat(first).matches(IdempotencyKeys.PATTERN);
        assertThat(IdempotencyKeys.resolve(4L, null, "{\"amount\":2}".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(first);
        assertThat(IdempotencyKeys.resolve(9L, null, body)).isNotEqualTo(first);
    }

    @Test
    void clientKeyIsKeptWhenItMatchesTheExistingRule() {
        assertThat(IdempotencyKeys.resolve(4L, "order-123", new byte[0])).isEqualTo("order-123");
    }

    @Test
    void invalidClientKeyIsRejected() {
        assertThatThrownBy(() -> IdempotencyKeys.resolve(4L, "bad key", new byte[0]))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
