package com.karimmajed.flowforge.exception;

public class NonRetryableExecutionException
        extends RuntimeException {

    public NonRetryableExecutionException(String message) {
        super(message);
    }

    public NonRetryableExecutionException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}