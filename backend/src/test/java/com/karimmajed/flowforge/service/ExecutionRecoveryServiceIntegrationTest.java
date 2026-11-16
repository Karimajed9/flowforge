package com.karimmajed.flowforge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JdbcTest(properties = "spring.config.import=optional:classpath:application.properties")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ExecutionRecoveryService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ExecutionRecoveryServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExecutionRecoveryService service;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(url).contains(":" + postgres.getMappedPort(5432));

        jdbc.execute("""
                DROP TRIGGER IF EXISTS flowforge_fail_outbox_insert
                ON execution_outbox
                """);
        jdbc.execute("""
                DROP FUNCTION IF EXISTS flowforge_fail_outbox_insert()
                """);
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    @Timeout(30)
    void concurrentRecoveryCreatesASingleOutboxEvent() throws Exception {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "QUEUED");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RecoveryResult>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Recovery threads did not start");
                    }
                    try {
                        service.requestRecovery(executionId);
                        return RecoveryResult.success();
                    } catch (ResponseStatusException ex) {
                        return RecoveryResult.rejected(ex);
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RecoveryResult> results = new ArrayList<>();
            for (Future<RecoveryResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn(RecoveryResult::succeeded).hasSize(1);
            assertThat(results)
                    .filteredOn(result -> !result.succeeded())
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.error().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(result.error().getReason()).isIn(
                                "A recovery was requested recently",
                                "Execution already has a pending outbox event");
                    });
            assertThat(attempts(executionId)).isEqualTo(1);
            assertThat(unpublishedOutboxCount(executionId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void maximumRecoveryAttemptsConflict() {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "QUEUED");
        jdbc.update("""
                UPDATE workflow_executions
                SET recovery_attempts = 3
                WHERE id = ?
                """,
                executionId);

        ResponseStatusException error = recoverAndExpectStatus(
                executionId,
                HttpStatus.CONFLICT);

        assertThat(error.getReason()).isEqualTo("Maximum recovery attempts reached");
        assertThat(attempts(executionId)).isEqualTo(3);
        assertThat(unpublishedOutboxCount(executionId)).isZero();
    }

    @Test
    void recoveryInsideTheCooldownConflicts() {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "QUEUED");
        jdbc.update("""
                UPDATE workflow_executions
                SET recovery_attempts = 1,
                    last_recovery_requested_at = CURRENT_TIMESTAMP - INTERVAL '9 minutes'
                WHERE id = ?
                """,
                executionId);

        ResponseStatusException error = recoverAndExpectStatus(
                executionId,
                HttpStatus.CONFLICT);

        assertThat(error.getReason()).isEqualTo("A recovery was requested recently");
        assertThat(attempts(executionId)).isEqualTo(1);
        assertThat(unpublishedOutboxCount(executionId)).isZero();
    }

    @Test
    void failedOutboxInsertRollsBackTheRecoveryCounter() {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "QUEUED");
        jdbc.execute("""
                CREATE FUNCTION flowforge_fail_outbox_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $func$
                BEGIN
                    RAISE EXCEPTION 'forced outbox insert failure';
                END;
                $func$
                """);
        jdbc.execute("""
                CREATE TRIGGER flowforge_fail_outbox_insert
                BEFORE INSERT ON execution_outbox
                FOR EACH ROW
                EXECUTE FUNCTION flowforge_fail_outbox_insert()
                """);

        assertThatThrownBy(() -> service.requestRecovery(executionId))
                .isInstanceOf(DataAccessException.class);

        assertThat(attempts(executionId)).isZero();
        assertThat(unpublishedOutboxCount(executionId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "SUCCEEDED", "FAILED"})
    void completedOrRunningExecutionCannotBeRecovered(String status) {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, status);

        ResponseStatusException error = recoverAndExpectStatus(
                executionId,
                HttpStatus.CONFLICT);

        assertThat(error.getReason())
                .isEqualTo("Execution cannot be recovered from status: " + status);
        assertThat(attempts(executionId)).isZero();
        assertThat(unpublishedOutboxCount(executionId)).isZero();
    }

    @Test
    void pendingOutboxEventBlocksAnotherRecovery() {
        long workflowId = insertWorkflow();
        long executionId = insertExecution(workflowId, "QUEUED");
        jdbc.update(
                "INSERT INTO execution_outbox (execution_id) VALUES (?)",
                executionId);

        ResponseStatusException error = recoverAndExpectStatus(
                executionId,
                HttpStatus.CONFLICT);

        assertThat(error.getReason()).isEqualTo("Execution already has a pending outbox event");
        assertThat(attempts(executionId)).isZero();
        assertThat(unpublishedOutboxCount(executionId)).isEqualTo(1);
    }

    @Test
    void missingExecutionIsNotFound() {
        ResponseStatusException error = recoverAndExpectStatus(
                9_999_999_999L,
                HttpStatus.NOT_FOUND);

        assertThat(error.getReason()).isEqualTo("Execution not found");
    }

    private ResponseStatusException recoverAndExpectStatus(
            long executionId,
            HttpStatus status) {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.requestRecovery(executionId));
        assertThat(error.getStatusCode()).isEqualTo(status);
        return error;
    }

    private long insertWorkflow() {
        return jdbc.queryForObject("""
                INSERT INTO workflows (name, active)
                VALUES (?, TRUE)
                RETURNING id
                """,
                Long.class,
                "Recovery " + UUID.randomUUID());
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
                "{}",
                "recovery-" + UUID.randomUUID());
    }

    private int attempts(long executionId) {
        return jdbc.queryForObject(
                "SELECT recovery_attempts FROM workflow_executions WHERE id = ?",
                Integer.class,
                executionId);
    }

    private int unpublishedOutboxCount(long executionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM execution_outbox
                WHERE execution_id = ?
                  AND published_at IS NULL
                """,
                Integer.class,
                executionId);
    }

    private record RecoveryResult(boolean succeeded, ResponseStatusException error) {

        private static RecoveryResult success() {
            return new RecoveryResult(true, null);
        }

        private static RecoveryResult rejected(ResponseStatusException error) {
            return new RecoveryResult(false, error);
        }
    }
}
