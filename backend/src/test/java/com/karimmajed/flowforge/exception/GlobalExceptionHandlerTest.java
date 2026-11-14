package com.karimmajed.flowforge.exception;

import org.hibernate.exception.ConstraintViolationException;

import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
        new GlobalExceptionHandler();

    @Test
    void duplicateStepPositionReturns409() {

        var exception = databaseException(
            "uq_workflow_step_position"
        );

        var response =
            handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().getDetail())
            .isEqualTo(
                "A step already exists at this position"
            );
    }

    @Test
    void duplicateIdempotencyKeyReturns409() {

        var exception = databaseException(
            "uq_execution_idempotency"
        );

        var response =
            handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        assertThat(response.getBody().getDetail())
            .contains("idempotency key");
    }

    @Test
    void unknownConstraintDoesNotBecome409() {

        var exception = databaseException(
            "some_other_constraint"
        );

        var response =
            handler.handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private DataIntegrityViolationException databaseException(
            String constraintName) {

        SQLException sqlException =
            new SQLException("Constraint violation", "23505");

        ConstraintViolationException hibernateException =
            new ConstraintViolationException(
                "Constraint violation",
                sqlException,
                ConstraintViolationException.ConstraintKind.UNIQUE,
                constraintName
            );

        return new DataIntegrityViolationException(
            "Database constraint failed",
            hibernateException
        );
    }
}