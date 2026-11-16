package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.enums.ExecutionStepStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JdbcTest(properties = "spring.config.import=optional:classpath:application.properties")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ExecutionStepHistoryService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ExecutionStepHistoryIntegrationTest {

    private static final String ATTEMPT_TOKEN = "attempt-token-secret-do-not-leak";

    private static final String SECRET_URL =
            "https://allowed.example/orders?token=super-secret-token";

    private static final OffsetDateTime STARTED =
            OffsetDateTime.parse("2026-11-15T18:00:00Z");

    private static final OffsetDateTime UPDATED =
            OffsetDateTime.parse("2026-11-15T18:00:04Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExecutionStepHistoryService history;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(url).contains(":" + postgres.getMappedPort(5432));

        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    void successfulWorkflowReturnsStepsInPositionOrder() {
        long workflowId = insertWorkflow();
        long third = insertStep(workflowId, "Customer", 3, "VALIDATE_FIELDS");
        long first = insertStep(workflowId, "Order", 1, "VALIDATE_FIELDS");
        long second = insertStep(workflowId, "Notify", 2, "HTTP_REQUEST");
        long executionId = insertExecution(workflowId, "SUCCEEDED");
        checkpoint(executionId, first, "SUCCEEDED", 1, null, STARTED, UPDATED);
        checkpoint(executionId, second, "SUCCEEDED", 1, null, STARTED, UPDATED);
        checkpoint(executionId, third, "SUCCEEDED", 1, null, STARTED, UPDATED);

        List<ExecutionStepResponse> steps = history.getSteps(executionId);

        assertThat(steps).extracting(ExecutionStepResponse::stepId)
                .containsExactly(first, second, third);
        assertThat(steps).extracting(ExecutionStepResponse::position)
                .containsExactly(1, 2, 3);
        assertThat(steps).extracting(ExecutionStepResponse::status)
                .containsOnly(ExecutionStepStatus.SUCCEEDED);
        assertThat(steps).allSatisfy(step -> {
            assertThat(step.attemptNumber()).isEqualTo(1);
            assertThat(step.failureReason()).isNull();
            assertThat(step.startedAt().toInstant()).isEqualTo(STARTED.toInstant());
            assertThat(step.updatedAt().toInstant()).isEqualTo(UPDATED.toInstant());
        });
        assertThat(rendered(steps)).doesNotContain(ATTEMPT_TOKEN, SECRET_URL);
        assertUnchanged(executionId, "SUCCEEDED");
    }

    @Test
    void failedStepLeavesLaterStepsNotStarted() {
        long workflowId = insertWorkflow();
        long first = insertStep(workflowId, "Order", 1, "VALIDATE_FIELDS");
        long second = insertStep(workflowId, "Notify", 2, "HTTP_REQUEST");
        long third = insertStep(workflowId, "Customer", 3, "VALIDATE_FIELDS");
        long executionId = insertExecution(workflowId, "FAILED");
        checkpoint(executionId, first, "SUCCEEDED", 1, null, STARTED, UPDATED);
        checkpoint(executionId, second, "FAILED", 1, "HTTP status 404", STARTED, UPDATED);

        List<ExecutionStepResponse> steps = history.getSteps(executionId);

        assertThat(steps).extracting(ExecutionStepResponse::name)
                .containsExactly("Order", "Notify", "Customer");
        assertThat(steps.get(0).status()).isEqualTo(ExecutionStepStatus.SUCCEEDED);
        assertThat(steps.get(0).failureReason()).isNull();
        assertThat(steps.get(1).status()).isEqualTo(ExecutionStepStatus.FAILED);
        assertThat(steps.get(1).attemptNumber()).isEqualTo(1);
        assertThat(steps.get(1).failureReason()).isEqualTo("HTTP status 404");
        assertThat(steps.get(2).status()).isEqualTo(ExecutionStepStatus.NOT_STARTED);
        assertThat(steps.get(2).stepId()).isEqualTo(third);
        assertThat(steps.get(2).attemptNumber()).isNull();
        assertThat(steps.get(2).failureReason()).isNull();
        assertThat(steps.get(2).startedAt()).isNull();
        assertThat(steps.get(2).updatedAt()).isNull();
        assertThat(steps.get(2).leaseUntil()).isNull();
        assertThat(rendered(steps)).doesNotContain(ATTEMPT_TOKEN, SECRET_URL);
    }

    @Test
    void incompleteWorkflowShowsTheRunningAttempt() {
        long workflowId = insertWorkflow();
        long first = insertStep(workflowId, "Order", 1, "VALIDATE_FIELDS");
        long second = insertStep(workflowId, "Notify", 2, "HTTP_REQUEST");
        long executionId = insertExecution(workflowId, "RUNNING");
        checkpoint(executionId, first, "SUCCEEDED", 1, null, STARTED, UPDATED);
        checkpoint(executionId, second, "RUNNING", 1, null, STARTED, STARTED);

        List<ExecutionStepResponse> steps = history.getSteps(executionId);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).status()).isEqualTo(ExecutionStepStatus.SUCCEEDED);
        assertThat(steps.get(1).stepId()).isEqualTo(second);
        assertThat(steps.get(1).status()).isEqualTo(ExecutionStepStatus.RUNNING);
        assertThat(steps.get(1).attemptNumber()).isEqualTo(1);
        assertThat(steps.get(1).failureReason()).isNull();
        assertThat(steps.get(1).startedAt().toInstant()).isEqualTo(STARTED.toInstant());
        assertThat(steps.get(1).updatedAt().toInstant()).isEqualTo(STARTED.toInstant());
        OffsetDateTime lease = jdbc.queryForObject("""
                SELECT lease_until
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                OffsetDateTime.class,
                executionId,
                second);
        assertThat(steps.get(1).leaseUntil().toInstant()).isEqualTo(lease.toInstant());
        jdbc.update("""
                UPDATE execution_step_checkpoints
                SET lease_until = NULL
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                executionId,
                first);
        assertThat(history.getSteps(executionId).get(0).leaseUntil()).isNull();
        assertUnchanged(executionId, "RUNNING");
        assertThat(checkpointStatus(executionId, second)).isEqualTo("RUNNING");
    }

    @Test
    void executionWithNoStepsReturnsAnEmptyList() {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "FAILED");

        assertThat(history.getSteps(executionId)).isEmpty();
    }

    @Test
    void missingExecutionIsNotFound() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> history.getSteps(9_999_999L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Execution not found");
    }

    private void assertUnchanged(long executionId, String status) {
        assertThat(jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId)).isEqualTo(status);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM execution_step_checkpoints
                WHERE attempt_token = ?
                """,
                Integer.class,
                ATTEMPT_TOKEN)).isPositive();
    }

    private String checkpointStatus(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT status
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId);
    }

    private static String rendered(List<ExecutionStepResponse> steps) {
        return steps.toString();
    }

    private long insertWorkflow() {
        return jdbc.queryForObject("""
                INSERT INTO workflows (name, active)
                VALUES (?, TRUE)
                RETURNING id
                """,
                Long.class,
                "History " + UUID.randomUUID());
    }

    private long insertStep(
            long workflowId,
            String name,
            int position,
            String stepType) {

        String configuration = "HTTP_REQUEST".equals(stepType)
                ? "{\"method\":\"GET\",\"url\":\"" + SECRET_URL + "\"}"
                : "{\"requiredFields\":[\"orderId\"]}";

        return jdbc.queryForObject("""
                INSERT INTO workflow_steps (
                    workflow_id, name, step_type, position, configuration
                )
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                workflowId,
                name,
                stepType,
                position,
                configuration);
    }

    private long insertExecution(long workflowId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_executions (
                    workflow_id, status, payload, idempotency_key
                )
                VALUES (?, ?, CAST(? AS jsonb), ?)
                RETURNING id
                """,
                Long.class,
                workflowId,
                status,
                "{\"orderId\":\"o-1\",\"token\":\"payload-secret\"}",
                "history-" + UUID.randomUUID());
    }

    private void checkpoint(
            long executionId,
            long stepId,
            String status,
            int attemptNumber,
            String failureReason,
            OffsetDateTime startedAt,
            OffsetDateTime updatedAt) {

        jdbc.update("""
                INSERT INTO execution_step_checkpoints (
                    execution_id,
                    workflow_step_id,
                    status,
                    attempt_token,
                    attempt_number,
                    lease_until,
                    failure_reason,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                    ?, ?, ?
                )
                """,
                executionId,
                stepId,
                status,
                ATTEMPT_TOKEN,
                attemptNumber,
                failureReason,
                startedAt,
                updatedAt);
    }
}
