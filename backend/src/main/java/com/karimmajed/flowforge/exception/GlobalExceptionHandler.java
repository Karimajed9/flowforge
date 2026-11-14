package com.karimmajed.flowforge.exception;

import org.hibernate.exception.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
        LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {

        if (hasConstraint(ex, "uq_workflow_step_position")) {
            return conflict(
                "Workflow step position conflict",
                "A step already exists at this position"
            );
        }

        if (hasConstraint(ex, "uq_execution_idempotency")) {
            return conflict(
                "Idempotency conflict",
                "An execution already exists with this " +
                "idempotency key"
            );
        }

        log.error("Unexpected database integrity violation", ex);

        ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "A database error occurred"
            );

        problem.setTitle("Database error");

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(problem);
    }

    private ResponseEntity<ProblemDetail> conflict(
            String title,
            String detail) {

        ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                detail
            );

        problem.setTitle(title);

        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(problem);
    }

    private boolean hasConstraint(
            Throwable exception,
            String constraintName) {

        Throwable cause = exception;

        while (cause != null) {

            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(
                        violation.getConstraintName())) {

                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}