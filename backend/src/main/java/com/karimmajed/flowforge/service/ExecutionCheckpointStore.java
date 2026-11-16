package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.enums.StepType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExecutionCheckpointStore {

    public static final Duration LEASE = Duration.ofMinutes(2);

    static final String NO_STEPS = "Workflow contains no steps";

    private static final TypeReference<Map<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private final JdbcTemplate jdbc;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public ExecutionCheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StepWork(
            long executionId,
            long stepId,
            String stepName,
            StepType stepType,
            Map<String, Object> configuration,
            Map<String, Object> payload,
            String attemptToken,
            int attemptNumber
    ) {}

    public enum Completion {
        NOT_OWNER,
        CONTINUE,
        FINISHED
    }

    public record Claim(Kind kind, StepWork work, String failureReason) {

        public enum Kind {
            NOT_FOUND,
            ALREADY_FINISHED,
            NO_WORK,
            BUSY,
            STEP
        }

        static Claim notFound() {
            return new Claim(Kind.NOT_FOUND, null, null);
        }

        static Claim alreadyFinished() {
            return new Claim(Kind.ALREADY_FINISHED, null, null);
        }

        static Claim noWork(String failureReason) {
            return new Claim(Kind.NO_WORK, null, failureReason);
        }

        static Claim busy() {
            return new Claim(Kind.BUSY, null, null);
        }

        static Claim step(StepWork work) {
            return new Claim(Kind.STEP, work, null);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claimNext(long executionId) {
        List<ExecutionRow> rows = jdbc.query("""
                SELECT status,
                       workflow_id,
                       payload::text AS payload
                FROM workflow_executions
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new ExecutionRow(
                        rs.getString("status"),
                        rs.getLong("workflow_id"),
                        readJson(rs.getString("payload"))),
                executionId);

        if (rows.isEmpty()) {
            return Claim.notFound();
        }

        ExecutionRow execution = rows.get(0);

        if (execution.status().equals("SUCCEEDED")
                || execution.status().equals("FAILED")) {
            return Claim.alreadyFinished();
        }

        List<StepRow> steps = loadSteps(execution.workflowId());
        List<CheckpointRow> checkpoints = loadCheckpoints(executionId);

        if (execution.status().equals("RECEIVED")
                || execution.status().equals("QUEUED")) {

            if (steps.isEmpty()) {
                markFailed(executionId, NO_STEPS);
                return Claim.noWork(NO_STEPS);
            }

            markRunning(executionId);
            return startStep(executionId, steps.get(0), execution.payload(), 1);
        }

        if (!execution.status().equals("RUNNING")) {
            throw new IllegalStateException(
                    "Unsupported execution status: " + execution.status());
        }

        Optional<CheckpointRow> failed = checkpoints.stream()
                .filter(checkpoint -> checkpoint.status().equals("FAILED"))
                .findFirst();

        if (failed.isPresent()) {
            markFailed(executionId, failed.get().failureReason());
            return Claim.alreadyFinished();
        }

        List<StepRow> remaining = steps.stream()
                .filter(step -> checkpoints.stream().noneMatch(checkpoint ->
                        checkpoint.stepId() == step.id()
                                && checkpoint.status().equals("SUCCEEDED")))
                .toList();

        if (remaining.isEmpty()) {
            markSucceeded(executionId);
            return Claim.alreadyFinished();
        }

        StepRow next = remaining.get(0);
        boolean alreadyClaimed = checkpoints.stream()
                .anyMatch(checkpoint -> checkpoint.stepId() == next.id());

        if (alreadyClaimed) {
            // A RUNNING checkpoint is left in place, including after its
            // lease expires. A later delivery must not start that step again.
            return Claim.busy();
        }

        return startStep(executionId, next, execution.payload(), 1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Completion complete(StepWork work) {
        if (!markOwned(work, "SUCCEEDED", null)) {
            return Completion.NOT_OWNER;
        }

        Long remaining = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM workflow_steps s
                WHERE s.workflow_id = (
                    SELECT workflow_id
                    FROM workflow_executions
                    WHERE id = ?
                )
                  AND NOT EXISTS (
                    SELECT 1
                    FROM execution_step_checkpoints c
                    WHERE c.execution_id = ?
                      AND c.workflow_step_id = s.id
                      AND c.status = 'SUCCEEDED'
                  )
                """,
                Long.class,
                work.executionId(),
                work.executionId());

        if (remaining != null && remaining > 0) {
            return Completion.CONTINUE;
        }

        int updated = markSucceeded(work.executionId());

        if (updated != 1) {
            throw new IllegalStateException(
                    "Execution status changed while completing "
                            + work.executionId());
        }

        return Completion.FINISHED;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(StepWork work, String reason) {
        String message = truncate(reason);

        if (!markOwned(work, "FAILED", message)) {
            return false;
        }

        int updated = markFailed(work.executionId(), message);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Execution status changed while failing "
                            + work.executionId());
        }

        return true;
    }

    private Claim startStep(
            long executionId,
            StepRow step,
            Map<String, Object> payload,
            int attemptNumber) {

        String token = UUID.randomUUID().toString();

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
                    ?, ?, 'RUNNING', ?, ?,
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                )
                """,
                executionId,
                step.id(),
                token,
                attemptNumber,
                LEASE.toSeconds());

        return Claim.step(new StepWork(
                executionId,
                step.id(),
                step.name(),
                step.stepType(),
                step.configuration(),
                payload,
                token,
                attemptNumber));
    }

    private boolean markOwned(StepWork work, String status, String failureReason) {
        int updated = jdbc.update("""
                UPDATE execution_step_checkpoints
                SET status = ?,
                    failure_reason = ?,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                  AND attempt_token = ?
                  AND attempt_number = ?
                  AND status = 'RUNNING'
                  AND lease_until > CURRENT_TIMESTAMP
                """,
                status,
                failureReason,
                work.executionId(),
                work.stepId(),
                work.attemptToken(),
                work.attemptNumber());

        return updated == 1;
    }

    private int markRunning(long executionId) {
        int updated = jdbc.update("""
                UPDATE workflow_executions
                SET status = 'RUNNING'
                WHERE id = ?
                  AND status IN ('RECEIVED', 'QUEUED')
                """,
                executionId);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Execution status changed while starting " + executionId);
        }

        return updated;
    }

    private int markSucceeded(long executionId) {
        return jdbc.update("""
                UPDATE workflow_executions
                SET status = 'SUCCEEDED',
                    failure_reason = NULL
                WHERE id = ?
                  AND status = 'RUNNING'
                """,
                executionId);
    }

    private int markFailed(long executionId, String reason) {
        return jdbc.update("""
                UPDATE workflow_executions
                SET status = 'FAILED',
                    failure_reason = ?
                WHERE id = ?
                  AND status IN ('RECEIVED', 'QUEUED', 'RUNNING')
                """,
                truncate(reason),
                executionId);
    }

    private List<StepRow> loadSteps(long workflowId) {
        return jdbc.query("""
                SELECT id,
                       name,
                       step_type,
                       configuration::text AS configuration
                FROM workflow_steps
                WHERE workflow_id = ?
                ORDER BY position ASC
                """,
                (rs, rowNum) -> new StepRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        StepType.valueOf(rs.getString("step_type")),
                        readJson(rs.getString("configuration"))),
                workflowId);
    }

    private List<CheckpointRow> loadCheckpoints(long executionId) {
        return jdbc.query("""
                SELECT workflow_step_id,
                       status,
                       failure_reason
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                """,
                (rs, rowNum) -> new CheckpointRow(
                        rs.getLong("workflow_step_id"),
                        rs.getString("status"),
                        rs.getString("failure_reason")),
                executionId);
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }

        try {
            return jsonMapper.readValue(json, JSON_MAP);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Could not read stored JSON", ex);
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }

        return reason.length() > 1000
                ? reason.substring(0, 1000)
                : reason;
    }

    private record ExecutionRow(
            String status,
            long workflowId,
            Map<String, Object> payload
    ) {}

    private record StepRow(
            long id,
            String name,
            StepType stepType,
            Map<String, Object> configuration
    ) {}

    private record CheckpointRow(
            long stepId,
            String status,
            String failureReason
    ) {}
}
