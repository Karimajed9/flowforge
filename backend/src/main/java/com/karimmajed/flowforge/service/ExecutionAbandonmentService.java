package com.karimmajed.flowforge.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Operator action for a checkpoint that is still RUNNING.
 * The caller must already have confirmed that the worker is no longer
 * active. Lease expiry is not proof of that, and this service does not
 * treat an expired lease as abandonment.
 *
 * <p>The previous attempt token is replaced so a late worker cannot
 * complete or fail the step. This does not call HTTP, insert an outbox
 * row, or requeue a message.
 */
@Service
public class ExecutionAbandonmentService {

    static final String ACTION = "ABANDON_RUNNING_STEP";

    static final String ACTOR = "recovery-key";

    static final String OUTCOME =
            "Abandoned by operator; external outcome may be unknown. ";

    private final JdbcTemplate jdbc;

    public ExecutionAbandonmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(long executionId, long stepId, int attemptNumber) {}

    @Transactional
    public Result abandon(long executionId, long stepId, int attemptNumber, String reason) {
        if (attemptNumber < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Attempt number must be positive");
        }

        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A reason is required");
        }

        String failureReason = OUTCOME + reason.trim();

        if (failureReason.length() > 1000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Reason is too long");
        }

        List<String> executionStatus = jdbc.query("""
                SELECT status
                FROM workflow_executions
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> rs.getString("status"),
                executionId);

        if (executionStatus.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Execution not found");
        }

        if (!"RUNNING".equals(executionStatus.get(0))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Execution cannot be abandoned from status: "
                            + executionStatus.get(0));
        }

        List<CheckpointMatch> checkpoints = jdbc.query("""
                SELECT status, attempt_number
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new CheckpointMatch(
                        rs.getString("status"),
                        rs.getInt("attempt_number")),
                executionId,
                stepId);

        if (checkpoints.isEmpty() || !"RUNNING".equals(checkpoints.get(0).status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Step is not running");
        }

        if (checkpoints.get(0).attemptNumber() != attemptNumber) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Attempt number does not match");
        }

        String replacementToken = UUID.randomUUID().toString();
        int checkpointUpdated = jdbc.update("""
                UPDATE execution_step_checkpoints
                SET status = 'FAILED',
                    failure_reason = ?,
                    attempt_token = ?,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                  AND attempt_number = ?
                  AND status = 'RUNNING'
                """,
                failureReason,
                replacementToken,
                executionId,
                stepId,
                attemptNumber);

        if (checkpointUpdated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Step is not running");
        }

        int executionUpdated = jdbc.update("""
                UPDATE workflow_executions
                SET status = 'FAILED',
                    failure_reason = ?
                WHERE id = ?
                  AND status = 'RUNNING'
                """,
                failureReason,
                executionId);

        if (executionUpdated != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Execution cannot be abandoned from status: RUNNING");
        }

        jdbc.update("""
                INSERT INTO execution_admin_audit (
                    execution_id,
                    workflow_step_id,
                    attempt_number,
                    action,
                    reason,
                    actor
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                executionId,
                stepId,
                attemptNumber,
                ACTION,
                failureReason,
                ACTOR);

        return new Result(executionId, stepId, attemptNumber);
    }

    private record CheckpointMatch(String status, int attemptNumber) {}
}
