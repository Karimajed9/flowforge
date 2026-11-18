package com.karimmajed.flowforge.exception;

import org.springframework.http.HttpStatus;

public class StepChangeException extends RuntimeException {

    private final HttpStatus status;

    private final String title;

    public StepChangeException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }
}
