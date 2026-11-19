package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Completion;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.StepWork;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

@JdbcTest(properties = "spring.config.import=optional:classpath:application.properties")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
        ExecutionCheckpointStore.class,
        ExecutionDefinitionStore.class,
        ExecutionLeaseRecovery.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ExecutionLeaseRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExecutionCheckpointStore store;

    @Autowired
    private ExecutionLeaseRecovery recovery;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(postgres.getMappedPort(5432)).isNotEqualTo(5433);
        assertThat(url).contains(":" + postgres.getMappedPort(5432));
        assertThat(url).doesNotContain(":5672");

        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_step_definitions");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    void anExpiredRunningClaimBecomesFailedWithoutReplaying() {
        long workflowId = workflow();
        long stepId = step(workflowId, "Order", "VALIDATE_FIELDS", 1);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, stepId, "Order", "VALIDATE_FIELDS", 1);
        String token = checkpoint(executionId, stepId, "RUNNING", true, null);

        assertThat(recovery.recoverBatch()).isEqualTo(1);

        String reason = "Execution lease expired during step Order; outcome may be unknown.";
        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(reason(executionId)).isEqualTo(reason);
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("FAILED");
        assertThat(checkpointReason(executionId, stepId)).isEqualTo(reason);
        assertThat(token(executionId, stepId)).isNotEqualTo(token);
        assertThat(lease(executionId, stepId)).isNull();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void aValidLeaseIsLeftRunning() {
        long workflowId = workflow();
        long stepId = step(workflowId, "Order", "VALIDATE_FIELDS", 1);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, stepId, "Order", "VALIDATE_FIELDS", 1);
        String token = checkpoint(executionId, stepId, "RUNNING", false, null);

        assertThat(recovery.recoverBatch()).isZero();
        assertThat(store.recoverIfExpired(executionId)).isFalse();

        assertThat(status(executionId)).isEqualTo("RUNNING");
        assertThat(reason(executionId)).isNull();
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("RUNNING");
        assertThat(token(executionId, stepId)).isEqualTo(token);
        assertThat(lease(executionId, stepId)).isNotNull();
    }

    @Test
    void concurrentRecoveryFailsTheClaimOnce() throws Exception {
        long workflowId = workflow();
        long stepId = step(workflowId, "Order", "VALIDATE_FIELDS", 1);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, stepId, "Order", "VALIDATE_FIELDS", 1);
        checkpoint(executionId, stepId, "RUNNING", true, null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> {
                start.await();
                return store.recoverIfExpired(executionId);
            });
            Future<Boolean> second = pool.submit(() -> {
                start.await();
                return store.recoverIfExpired(executionId);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(checkpointCount(executionId)).isEqualTo(1);
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("FAILED");
        assertThat(recovery.recoverBatch()).isZero();
        String savedToken = token(executionId, stepId);
        String savedReason = reason(executionId);
        assertThat(recovery.recoverBatch()).isZero();
        assertThat(token(executionId, stepId)).isEqualTo(savedToken);
        assertThat(reason(executionId)).isEqualTo(savedReason);
        assertThat(checkpointReason(executionId, stepId)).isEqualTo(savedReason);
    }

    @Test
    void aLateCompletionCannotOverwriteTheRecoveredFailure() {
        long workflowId = workflow();
        long stepId = step(workflowId, "Order", "VALIDATE_FIELDS", 1);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, stepId, "Order", "VALIDATE_FIELDS", 1);
        String token = checkpoint(executionId, stepId, "RUNNING", true, null);

        assertThat(store.recoverIfExpired(executionId)).isTrue();
        String recoveredToken = token(executionId, stepId);
        String recoveredReason = reason(executionId);

        StepWork stale = new StepWork(
                executionId,
                stepId,
                "Order",
                StepType.VALIDATE_FIELDS,
                Map.of("requiredFields", List.of("email")),
                Map.of("email", "ada@example.com"),
                token,
                1);

        assertThat(store.complete(stale)).isEqualTo(Completion.NOT_OWNER);
        assertThat(store.fail(stale, "worker finished late")).isFalse();

        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(reason(executionId)).isEqualTo(recoveredReason);
        assertThat(token(executionId, stepId)).isEqualTo(recoveredToken);
        assertThat(checkpointStatus(executionId, stepId)).isEqualTo("FAILED");
        assertThat(checkpointReason(executionId, stepId)).isEqualTo(recoveredReason);
    }

    @Test
    void recoveryAfterRestartUsesOnlyTheDatabase() {
        long workflowId = workflow();
        long stepId = step(workflowId, "Order", "VALIDATE_FIELDS", 1);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, stepId, "Order", "VALIDATE_FIELDS", 1);
        checkpoint(executionId, stepId, "RUNNING", true, null);

        ExecutionLeaseRecovery restarted = new ExecutionLeaseRecovery(store, 20);

        assertThat(restarted.recoverBatch()).isEqualTo(1);
        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(reason(executionId))
                .isEqualTo("Execution lease expired during step Order; outcome may be unknown.");
        assertThat(outboxCount()).isZero();
    }

    @Test
    void terminalExecutionsAreNotReopened() {
        long workflowId = workflow();
        long doneStep = step(workflowId, "Done", "VALIDATE_FIELDS", 1);
        long failedStep = step(workflowId, "Failed", "VALIDATE_FIELDS", 2);
        long succeededId = execution(workflowId, "SUCCEEDED");
        long failedId = execution(workflowId, "FAILED");
        jdbc.update(
                "UPDATE workflow_executions SET failure_reason = ? WHERE id = ?",
                "already failed",
                failedId);
        definition(succeededId, doneStep, "Done", "VALIDATE_FIELDS", 1);
        definition(failedId, failedStep, "Failed", "VALIDATE_FIELDS", 1);
        checkpoint(succeededId, doneStep, "SUCCEEDED", false, null);
        String failedToken = checkpoint(failedId, failedStep, "RUNNING", true, null);

        assertThat(recovery.recoverBatch()).isZero();
        assertThat(store.recoverIfExpired(succeededId)).isFalse();
        assertThat(store.recoverIfExpired(failedId)).isFalse();

        assertThat(status(succeededId)).isEqualTo("SUCCEEDED");
        assertThat(reason(succeededId)).isNull();
        assertThat(checkpointStatus(succeededId, doneStep)).isEqualTo("SUCCEEDED");
        assertThat(status(failedId)).isEqualTo("FAILED");
        assertThat(reason(failedId)).isEqualTo("already failed");
        assertThat(checkpointStatus(failedId, failedStep)).isEqualTo("RUNNING");
        assertThat(token(failedId, failedStep)).isEqualTo(failedToken);
    }

    @Test
    void anExpiredConditionDoesNotSkipEitherBranch() {
        long workflowId = workflow();
        long conditionId = step(workflowId, "Amount", "CONDITION", 1);
        long notifyId = step(workflowId, "Notify", "VALIDATE_FIELDS", 2);
        long recordId = step(workflowId, "Record", "VALIDATE_FIELDS", 3);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, conditionId, "Amount", "CONDITION", 1);
        definition(executionId, notifyId, "Notify", "VALIDATE_FIELDS", 2);
        definition(executionId, recordId, "Record", "VALIDATE_FIELDS", 3);
        String token = checkpoint(executionId, conditionId, "RUNNING", true, null);

        assertThat(store.recoverIfExpired(executionId)).isTrue();

        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(reason(executionId))
                .isEqualTo("Execution lease expired during step Amount; outcome may be unknown.");
        assertThat(checkpointStatus(executionId, conditionId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND status = 'SKIPPED'
                """,
                Integer.class,
                executionId)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT branch_taken
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                Boolean.class,
                executionId,
                conditionId)).isNull();

        StepWork stale = new StepWork(
                executionId,
                conditionId,
                "Amount",
                StepType.CONDITION,
                Map.of(
                        "field", "amount",
                        "operator", "EQUALS",
                        "value", 10,
                        "trueSteps", List.of(notifyId),
                        "falseSteps", List.of(recordId)),
                Map.of("amount", 10),
                token,
                1);
        assertThat(store.completeCondition(stale, true, List.of(recordId)))
                .isEqualTo(Completion.NOT_OWNER);
        assertThat(checkpointCount(executionId)).isEqualTo(1);
    }

    @Test
    void recoveryLeavesAnAlreadySkippedBranchAlone() {
        long workflowId = workflow();
        long conditionId = step(workflowId, "Amount", "CONDITION", 1);
        long notifyId = step(workflowId, "Notify", "VALIDATE_FIELDS", 2);
        long recordId = step(workflowId, "Record", "VALIDATE_FIELDS", 3);
        long finishId = step(workflowId, "Finish", "VALIDATE_FIELDS", 4);
        long executionId = execution(workflowId, "RUNNING");
        definition(executionId, conditionId, "Amount", "CONDITION", 1);
        definition(executionId, notifyId, "Notify", "VALIDATE_FIELDS", 2);
        definition(executionId, recordId, "Record", "VALIDATE_FIELDS", 3);
        definition(executionId, finishId, "Finish", "VALIDATE_FIELDS", 4);
        checkpoint(executionId, conditionId, "SUCCEEDED", false, true);
        checkpoint(executionId, notifyId, "SUCCEEDED", false, null);
        checkpoint(executionId, recordId, "SKIPPED", false, null);
        checkpoint(executionId, finishId, "RUNNING", true, null);

        assertThat(recovery.recoverBatch()).isEqualTo(1);
        assertThat(recovery.recoverBatch()).isZero();

        assertThat(status(executionId)).isEqualTo("FAILED");
        assertThat(reason(executionId))
                .isEqualTo("Execution lease expired during step Finish; outcome may be unknown.");
        assertThat(checkpointStatus(executionId, conditionId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                """
                SELECT branch_taken
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                Boolean.class,
                executionId,
                conditionId)).isTrue();
        assertThat(checkpointStatus(executionId, notifyId)).isEqualTo("SUCCEEDED");
        assertThat(checkpointStatus(executionId, recordId)).isEqualTo("SKIPPED");
        assertThat(checkpointStatus(executionId, finishId)).isEqualTo("FAILED");
        assertThat(checkpointCount(executionId)).isEqualTo(4);
    }

    private long workflow() {
        return jdbc.queryForObject("""
                INSERT INTO workflows (name, active)
                VALUES (?, TRUE)
                RETURNING id
                """,
                Long.class,
                "Lease " + UUID.randomUUID());
    }

    private long step(long workflowId, String name, String type, int position) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_steps (
                    workflow_id, name, step_type, position, configuration
                )
                VALUES (?, ?, ?, ?, CAST('{}' AS jsonb))
                RETURNING id
                """,
                Long.class,
                workflowId,
                name,
                type,
                position);
    }

    private long execution(long workflowId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_executions (
                    workflow_id, status, payload, idempotency_key, definition_frozen
                )
                VALUES (?, ?, CAST('{}' AS jsonb), ?, TRUE)
                RETURNING id
                """,
                Long.class,
                workflowId,
                status,
                "lease-" + UUID.randomUUID());
    }

    private void definition(
            long executionId,
            long stepId,
            String name,
            String type,
            int position) {

        jdbc.update("""
                INSERT INTO execution_step_definitions (
                    execution_id, workflow_step_id, name, step_type, position, configuration
                )
                VALUES (?, ?, ?, ?, ?, CAST('{}' AS jsonb))
                """,
                executionId,
                stepId,
                name,
                type,
                position);
    }

    private String checkpoint(
            long executionId,
            long stepId,
            String status,
            boolean expired,
            Boolean branchTaken) {

        String attempt = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO execution_step_checkpoints (
                    execution_id,
                    workflow_step_id,
                    status,
                    attempt_token,
                    attempt_number,
                    lease_until,
                    branch_taken
                )
                VALUES (
                    ?, ?, ?, ?, 1,
                    CASE WHEN CAST(? AS boolean)
                         THEN CURRENT_TIMESTAMP - INTERVAL '1 minute'
                         ELSE CURRENT_TIMESTAMP + INTERVAL '2 minutes' END,
                    CAST(? AS boolean)
                )
                """,
                executionId,
                stepId,
                status,
                attempt,
                expired,
                branchTaken);
        return attempt;
    }

    private String status(long executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private String reason(long executionId) {
        return jdbc.queryForObject(
                "SELECT failure_reason FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
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

    private String checkpointReason(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT failure_reason
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId);
    }

    private String token(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT attempt_token
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId);
    }

    private Object lease(long executionId, long stepId) {
        return jdbc.queryForObject("""
                SELECT lease_until
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                Object.class,
                executionId,
                stepId);
    }

    private int checkpointCount(long executionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_step_checkpoints WHERE execution_id = ?",
                Integer.class,
                executionId);
        return count == null ? 0 : count;
    }

    private int outboxCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_outbox",
                Integer.class);
        return count == null ? 0 : count;
    }
}
