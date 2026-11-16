package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.repository.OutboxDeliveryStore;
import com.karimmajed.flowforge.repository.OutboxDeliveryStore.PendingOutbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JdbcTest(properties = "spring.config.import=optional:classpath:application.properties")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
        ExecutionCheckpointStore.class,
        WorkflowExecutionProcessor.class,
        OutboxDeliveryStore.class,
        ExecutionRecoveryService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkflowExecutionCheckpointIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkflowExecutionProcessor processor;

    @Autowired
    private OutboxDeliveryStore outbox;

    @Autowired
    private ExecutionRecoveryService recovery;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(url).contains(":" + postgres.getMappedPort(5432));

        processor.setBeforeClaim(null);
        processor.setOnStepStart(null);

        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    void multiStepValidationSucceedsAndRecordsCheckpoints() {
        long workflowId = insertWorkflow();
        long first = insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long second = insertStep(workflowId, "Customer", 2, "[\"customerId\"]");
        long executionId = insertExecution(
                workflowId,
                "QUEUED",
                "{\"orderId\":\"o-1\",\"customerId\":\"c-1\"}");
        List<Long> started = new ArrayList<>();
        processor.setOnStepStart(work -> started.add(work.stepId()));

        processor.process(executionId);

        assertThat(started).containsExactly(first, second);
        assertThat(executionStatus(executionId)).isEqualTo("SUCCEEDED");
        assertThat(failureReason(executionId)).isNull();
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(first, "SUCCEEDED", 1, null),
                new SavedCheckpoint(second, "SUCCEEDED", 1, null));
    }

    @Test
    void laterValidationFailureKeepsTheEarlierCheckpoint() {
        long workflowId = insertWorkflow();
        long first = insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long second = insertStep(workflowId, "Customer", 2, "[\"customerId\"]");
        long executionId = insertExecution(
                workflowId,
                "QUEUED",
                "{\"orderId\":\"o-1\"}");

        processor.process(executionId);

        assertThat(executionStatus(executionId)).isEqualTo("FAILED");
        assertThat(failureReason(executionId))
                .isEqualTo("Missing required field: customerId");
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(first, "SUCCEEDED", 1, null),
                new SavedCheckpoint(second, "FAILED", 1, "Missing required field: customerId"));
    }

    @Test
    void completedStepsAreNotRunAgainAfterRedelivery() {
        long workflowId = insertWorkflow();
        long first = insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long second = insertStep(workflowId, "Customer", 2, "[\"customerId\"]");
        long executionId = insertExecution(
                workflowId,
                "QUEUED",
                "{\"orderId\":\"o-1\",\"customerId\":\"c-1\"}");
        List<Long> started = new ArrayList<>();
        AtomicInteger claims = new AtomicInteger();
        processor.setBeforeClaim(() -> {
            if (claims.incrementAndGet() == 2) {
                throw new IllegalStateException("simulated crash");
            }
        });
        processor.setOnStepStart(work -> started.add(work.stepId()));

        assertThrows(IllegalStateException.class, () -> processor.process(executionId));

        assertThat(started).containsExactly(first);
        assertThat(executionStatus(executionId)).isEqualTo("RUNNING");
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(first, "SUCCEEDED", 1, null));

        processor.setBeforeClaim(null);
        processor.process(executionId);

        assertThat(started).containsExactly(first, second);
        assertThat(executionStatus(executionId)).isEqualTo("SUCCEEDED");
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(first, "SUCCEEDED", 1, null),
                new SavedCheckpoint(second, "SUCCEEDED", 1, null));
    }

    @Test
    @Timeout(30)
    void concurrentClaimsAllowOneWorker() throws Exception {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long executionId = insertExecution(
                workflowId,
                "QUEUED",
                "{\"orderId\":\"o-1\"}");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        processor.setOnStepStart(work -> {
            starts.incrementAndGet();
            entered.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures = List.of(
                    pool.submit(() -> runProcess(executionId)),
                    pool.submit(() -> runProcess(executionId)));

            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            String firstFinished = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (firstFinished == null && System.nanoTime() < deadline) {
                for (Future<String> future : futures) {
                    if (future.isDone()) {
                        firstFinished = future.get();
                        break;
                    }
                }
                if (firstFinished == null) {
                    Thread.sleep(20);
                }
            }

            assertThat(firstFinished).startsWith("409 ");
            release.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder("ok", firstFinished);
            assertThat(starts).hasValue(1);
            assertThat(executionStatus(executionId)).isEqualTo("SUCCEEDED");
            assertThat(checkpoints(executionId)).containsExactly(
                    new SavedCheckpoint(stepId, "SUCCEEDED", 1, null));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void crashAfterClaimLeavesTheStepRunning() {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long executionId = insertExecution(
                workflowId,
                "RECEIVED",
                "{\"orderId\":\"o-1\"}");
        AtomicInteger starts = new AtomicInteger();
        processor.setOnStepStart(work -> {
            starts.incrementAndGet();
            throw new IllegalStateException("simulated crash");
        });

        assertThrows(IllegalStateException.class, () -> processor.process(executionId));

        String token = attemptToken(executionId);
        assertThat(token).isNotBlank();
        assertThat(leaseUntil(executionId)).isNotNull();
        assertThat(executionStatus(executionId)).isEqualTo("RUNNING");
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(stepId, "RUNNING", 1, null));

        jdbc.update("""
                UPDATE execution_step_checkpoints
                SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE execution_id = ?
                """,
                executionId);
        processor.setOnStepStart(null);
        IllegalStateException redelivery = assertThrows(
                IllegalStateException.class,
                () -> processor.process(executionId));

        assertThat(redelivery.getMessage())
                .isEqualTo("Unexpected RUNNING execution: " + executionId);
        assertThat(starts).hasValue(1);
        assertThat(attemptToken(executionId)).isEqualTo(token);
        assertThat(executionStatus(executionId)).isEqualTo("RUNNING");
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(stepId, "RUNNING", 1, null));
    }

    @Test
    void staleAttemptCannotOverwriteANewerAttempt() {
        long workflowId = insertWorkflow();
        long stepId = insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long executionId = insertExecution(
                workflowId,
                "QUEUED",
                "{\"orderId\":\"o-1\"}");
        processor.setOnStepStart(work -> jdbc.update("""
                UPDATE execution_step_checkpoints
                SET attempt_token = ?,
                    attempt_number = attempt_number + 1,
                    lease_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes'
                WHERE execution_id = ?
                """,
                "newer-attempt",
                executionId));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> processor.process(executionId));

        assertThat(ex.getMessage())
                .isEqualTo("Lost attempt ownership for execution " + executionId);
        assertThat(executionStatus(executionId)).isEqualTo("RUNNING");
        assertThat(failureReason(executionId)).isNull();
        assertThat(attemptToken(executionId)).isEqualTo("newer-attempt");
        assertThat(checkpoints(executionId)).containsExactly(
                new SavedCheckpoint(stepId, "RUNNING", 2, null));
    }

    @Test
    void duplicateDeliveryOfACompletedExecutionDoesNotRestartIt() {
        long workflowId = insertWorkflow();
        insertStep(workflowId, "Order", 1, "[\"orderId\"]");
        long executionId = insertExecution(
                workflowId,
                "QUEUED",
                "{\"orderId\":\"o-1\"}");
        AtomicInteger starts = new AtomicInteger();
        processor.setOnStepStart(work -> starts.incrementAndGet());

        processor.process(executionId);
        List<SavedCheckpoint> saved = checkpoints(executionId);

        processor.process(executionId);

        assertThat(starts).hasValue(1);
        assertThat(executionStatus(executionId)).isEqualTo("SUCCEEDED");
        assertThat(checkpoints(executionId)).isEqualTo(saved);

        long failedId = insertExecution(workflowId, "FAILED", "{\"orderId\":\"o-1\"}");
        jdbc.update("""
                UPDATE workflow_executions
                SET failure_reason = ?
                WHERE id = ?
                """,
                "Missing required field: customerId",
                failedId);

        processor.process(failedId);

        assertThat(executionStatus(failedId)).isEqualTo("FAILED");
        assertThat(failureReason(failedId))
                .isEqualTo("Missing required field: customerId");
        assertThat(checkpoints(failedId)).isEmpty();
        assertThat(starts).hasValue(1);
    }

    @Test
    void outboxPublishDoesNotOverwriteANewerExecutionStatus() {
        long workflowId = insertWorkflow();
        long receivedId = insertExecution(workflowId, "RECEIVED", "{}");
        long runningId = insertExecution(workflowId, "RUNNING", "{}");
        long succeededId = insertExecution(workflowId, "SUCCEEDED", "{}");
        long failedId = insertExecution(workflowId, "FAILED", "{}");
        jdbc.update(
                "UPDATE workflow_executions SET failure_reason = ? WHERE id = ?",
                "already failed",
                failedId);

        markPublished(receivedId);
        markPublished(runningId);
        markPublished(succeededId);
        markPublished(failedId);

        assertThat(executionStatus(receivedId)).isEqualTo("QUEUED");
        assertThat(executionStatus(runningId)).isEqualTo("RUNNING");
        assertThat(executionStatus(succeededId)).isEqualTo("SUCCEEDED");
        assertThat(executionStatus(failedId)).isEqualTo("FAILED");
        assertThat(failureReason(failedId)).isEqualTo("already failed");
    }

    @Test
    void recoveryStillRejectsARunningExecution() {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "RUNNING", "{}");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> recovery.requestRecovery(executionId));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason())
                .isEqualTo("Execution cannot be recovered from status: RUNNING");
        assertThat(executionStatus(executionId)).isEqualTo("RUNNING");
    }

    @Test
    void webhookIdempotencyKeyRemainsUnique() {
        long workflowId = insertWorkflow();
        insertExecution(workflowId, "QUEUED", "{}", "same-event");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertExecution(workflowId, "QUEUED", "{}", "same-event"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_executions WHERE workflow_id = ?",
                Integer.class,
                workflowId);
        assertThat(count).isEqualTo(1);
    }

    private String runProcess(long executionId) {
        try {
            processor.process(executionId);
            return "ok";
        } catch (IllegalStateException ex) {
            return "409 " + ex.getMessage();
        }
    }

    private void markPublished(long executionId) {
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO execution_outbox (execution_id, claim_token, claimed_until)
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '2 minutes')
                """,
                executionId,
                token);
        Long outboxId = jdbc.queryForObject(
                "SELECT id FROM execution_outbox WHERE claim_token = ?",
                Long.class,
                token);
        assertThat(outbox.markPublished(new PendingOutbox(outboxId, executionId), token))
                .isTrue();
    }

    private long insertWorkflow() {
        return jdbc.queryForObject("""
                INSERT INTO workflows (name, active)
                VALUES (?, TRUE)
                RETURNING id
                """,
                Long.class,
                "Checkpoint " + UUID.randomUUID());
    }

    private long insertStep(
            long workflowId,
            String name,
            int position,
            String requiredFields) {

        return jdbc.queryForObject("""
                INSERT INTO workflow_steps (
                    workflow_id, name, step_type, position, configuration
                )
                VALUES (?, ?, 'VALIDATE_FIELDS', ?, CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                workflowId,
                name,
                position,
                "{\"requiredFields\":" + requiredFields + "}");
    }

    private long insertExecution(long workflowId, String status, String payload) {
        return insertExecution(
                workflowId,
                status,
                payload,
                "checkpoint-" + UUID.randomUUID());
    }

    private long insertExecution(
            long workflowId,
            String status,
            String payload,
            String idempotencyKey) {

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
                payload,
                idempotencyKey);
    }

    private String executionStatus(long executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private String failureReason(long executionId) {
        return jdbc.queryForObject(
                "SELECT failure_reason FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private String attemptToken(long executionId) {
        return jdbc.queryForObject("""
                SELECT attempt_token
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                """,
                String.class,
                executionId);
    }

    private OffsetDateTime leaseUntil(long executionId) {
        return jdbc.queryForObject("""
                SELECT lease_until
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                """,
                OffsetDateTime.class,
                executionId);
    }

    private List<SavedCheckpoint> checkpoints(long executionId) {
        return jdbc.query("""
                SELECT workflow_step_id,
                       status,
                       attempt_number,
                       failure_reason
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new SavedCheckpoint(
                        rs.getLong("workflow_step_id"),
                        rs.getString("status"),
                        rs.getInt("attempt_number"),
                        rs.getString("failure_reason")),
                executionId);
    }

    private record SavedCheckpoint(
            long stepId,
            String status,
            int attemptNumber,
            String failureReason
    ) {}
}
