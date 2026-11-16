package com.karimmajed.flowforge.http;

import java.time.Duration;

public record HttpGetResult(
        Outcome outcome,
        Integer statusCode,
        Duration duration) {

    public HttpGetResult {
        if (outcome == null || duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(
                    "A result needs an outcome and a non-negative duration");
        }
    }

    public enum Outcome {
        SUCCESS,
        HTTP_STATUS,
        TIMEOUT,
        NETWORK,
        BLOCKED,
        RESPONSE_TOO_LARGE
    }
}
