package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Completion;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.StepWork;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JdbcTest(properties = "spring.config.import=optional:classpath:application.properties")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
        ExecutionAbandonmentService.class,
        ExecutionCheckpointStore.class,
        ExecutionDefinitionStore.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ExecutionAbandonmentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExecutionAbandonmentService abandonment;

    @Autowired
    private ExecutionCheckpointStore store;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(url).contains(":" + postgres.getMappedPort(5432));

        jdbc.update("DELETE FROM execution_admin_audit");
        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    void operatorCanAbandonARunningStep() {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId);
        long executionId = insertExecution(workflowId, "RUNNING");
        String token = "attempt-token-secret";
        checkpoint(executionId, stepId, "RUNNING", 1, token, "2 minutes");

        ExecutionAbandonmentService.Result result = abandonment.abandon(
                executionId,
                stepId,
                1,
                "worker process is gone");

        assertThat(result.attemptNumber()).isEqualTo(1);
        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("FAILED");
        assertThat(failureReason(executionId))
                .contains("external outcome may be unknown")
                .contains("worker process is gone");
        assertThat(attemptToken(executionId, stepId)).isNotEqualTo(token);
        assertThat(lease(executionId, stepId)).isNull();
        assertThat(outboxCount()).isZero();
        assertThat(auditCount()).isEqualTo(1);
        assertThat(auditAction()).isEqualTo(ExecutionAbandonmentService.ACTION);
        assertThat(auditActor()).isEqualTo(ExecutionAbandonmentService.ACTOR);
        assertThat(auditActor()).doesNotContain("secret");
        assertThat(auditCreatedAt()).isNotNull();
    }

    @Test
    @Timeout(30)
    void concurrentAbandonmentAllowsOneDecision() throws Exception {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId);
        long executionId = insertExecution(workflowId, "RUNNING");
        checkpoint(executionId, stepId, "RUNNING", 1, "token-1", "2 minutes");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<String>> futures = List.of(
                    pool.submit(() -> abandonAfter(start, executionId, stepId, 1)),
                    pool.submit(() -> abandonAfter(start, executionId, stepId, 1)));
            start.countDown();

            assertThat(List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            "ok",
                            "409 Execution cannot be abandoned from status: FAILED");
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(auditCount()).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void lateWorkerCannotOverwriteTheAbandonment() {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId);
        long executionId = insertExecution(workflowId, "RUNNING");
        String token = "attempt-token-secret";
        checkpoint(executionId, stepId, "RUNNING", 1, token, "2 minutes");

        abandonment.abandon(executionId, stepId, 1, "worker process is gone");

        Completion completion = store.complete(new StepWork(
                executionId,
                stepId,
                "Notify",
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", "https://allowed.example/orders"),
                Map.of(),
                token,
                1));

        assertThat(completion).isEqualTo(Completion.NOT_OWNER);
        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("FAILED");
        assertThat(attemptToken(executionId, stepId)).isNotEqualTo(token);
        assertThat(outboxCount()).isZero();
    }

    @Test
    void wrongAttemptNumberDoesNotChangeTheStep() {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId);
        long executionId = insertExecution(workflowId, "RUNNING");
        checkpoint(executionId, stepId, "RUNNING", 1, "token-1", "2 minutes");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> abandonment.abandon(executionId, stepId, 2, "wrong attempt"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("Attempt number does not match");
        assertThat(status(executionId)).isEqualTo("RUNNING");
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("RUNNING");
        assertThat(attemptToken(executionId, stepId)).isEqualTo("token-1");
        assertThat(auditCount()).isZero();
    }

    @Test
    void succeededAndFailedRowsAreLeftAlone() {
        long workflowId = insertWorkflow();
        long succeededStep = insertStep(workflowId);
        long succeededExecution = insertExecution(workflowId, "SUCCEEDED");
        checkpoint(succeededExecution, succeededStep, "SUCCEEDED", 1, "token-s", "2 minutes");

        ResponseStatusException succeeded = assertThrows(
                ResponseStatusException.class,
                () -> abandonment.abandon(
                        succeededExecution,
                        succeededStep,
                        1,
                        "too late"));

        assertThat(succeeded.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(succeeded.getReason())
                .isEqualTo("Execution cannot be abandoned from status: SUCCEEDED");
        assertThat(status(succeededExecution)).isEqualTo("SUCCEEDED");
        assertThat(checkpointStatus(succeededExecution, succeededStep)).isEqualTo("SUCCEEDED");

        long failedStep = insertStep(workflowId);
        long failedExecution = insertExecution(workflowId, "FAILED");
        checkpoint(failedExecution, failedStep, "FAILED", 1, "token-f", "2 minutes");

        ResponseStatusException failed = assertThrows(
                ResponseStatusException.class,
                () -> abandonment.abandon(failedExecution, failedStep, 1, "too late"));

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failed.getReason())
                .isEqualTo("Execution cannot be abandoned from status: FAILED");
        assertThat(checkpointStatus(failedExecution, failedStep)).isEqualTo("FAILED");
        assertThat(auditCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void expiredLeaseStaysRunningUntilTheOperatorAbandonsIt() {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId);
        long executionId = insertExecution(workflowId, "RUNNING");
        checkpoint(executionId, stepId, "RUNNING", 1, "token-1", "-1 minutes");

        assertThat(status(executionId)).isEqualTo("RUNNING");

        abandonment.abandon(executionId, stepId, 1, "lease expired and the worker is gone");

        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(failureReason(executionId))
                .contains("external outcome may be unknown");
        assertThat(outboxCount()).isZero();
    }

    private String abandonAfter(
            CountDownLatch start,
            long executionId,
            long stepId,
            int attemptNumber) {

        try {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            abandonment.abandon(executionId, stepId, attemptNumber, "one operator");
            return "ok";
        } catch (ResponseStatusException ex) {
            return ex.getStatusCode().value() + " " + ex.getReason();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private long insertWorkflow() {
        return jdbc.queryForObject("""
                INSERT INTO workflows (name, active)
                VALUES (?, TRUE)
                RETURNING id
                """,
                Long.class,
                "Abandon " + UUID.randomUUID());
    }

    private long insertStep(long workflowId) {
        Integer position = jdbc.queryForObject(
                "SELECT COALESCE(MAX(position), 0) + 1 FROM workflow_steps WHERE workflow_id = ?",
                Integer.class,
                workflowId);
        return jdbc.queryForObject("""
                INSERT INTO workflow_steps (
                    workflow_id, name, step_type, position, configuration
                )
                VALUES (?, 'Notify', 'HTTP_REQUEST', ?, CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                workflowId,
                position,
                "{\"method\":\"GET\",\"url\":\"https://allowed.example/orders\"}");
    }

    private long insertExecution(long workflowId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_executions (
                    workflow_id, status, payload, idempotency_key
                )
                VALUES (?, ?, CAST('{}' AS jsonb), ?)
                RETURNING id
                """,
                Long.class,
                workflowId,
                status,
                "abandon-" + UUID.randomUUID());
    }

    private void checkpoint(
            long executionId,
            long stepId,
            String status,
            int attemptNumber,
            String token,
            String leaseInterval) {

        jdbc.update("""
                INSERT INTO execution_step_checkpoints (
                    execution_id,
                    workflow_step_id,
                    status,
                    attempt_token,
                    attempt_number,
                    lease_until
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 minute')
                )
                """,
                executionId,
                stepId,
                status,
                token,
                attemptNumber,
                Integer.parseInt(leaseInterval.replace(" minutes", "").replace(" minute", "")));
    }

    private String status(long executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private String checkpointStatus(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT status
                FROM execution_step_checkpoints
                WHERE execution_id = ? AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId);
    }

    private String failureReason(long executionId) {
        return jdbc.queryForObject(
                "SELECT failure_reason FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private String attemptToken(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT attempt_token
                FROM execution_step_checkpoints
                WHERE execution_id = ? AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId);
    }

    private Object lease(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT lease_until
                FROM execution_step_checkpoints
                WHERE execution_id = ? AND workflow_step_id = ?
                """,
                Object.class,
                executionId,
                stepId);
    }

    private int outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM execution_outbox", Integer.class);
    }

    private int auditCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_admin_audit",
                Integer.class);
    }

    private String auditAction() {
        return jdbc.queryForObject(
                "SELECT action FROM execution_admin_audit",
                String.class);
    }

    private String auditActor() {
        return jdbc.queryForObject(
                "SELECT actor FROM execution_admin_audit",
                String.class);
    }

    private Object auditCreatedAt() {
        return jdbc.queryForObject(
                "SELECT created_at FROM execution_admin_audit",
                Object.class);
    }
}
