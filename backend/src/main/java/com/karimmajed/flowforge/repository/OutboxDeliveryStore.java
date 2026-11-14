package com.karimmajed.flowforge.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class OutboxDeliveryStore {

    private final JdbcTemplate jdbc;

    public OutboxDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingOutbox(
            long outboxId,
            long executionId
    ) {}

    @Transactional
    public List<PendingOutbox> claimBatch(
            String claimToken,
            int batchSize) {

        return jdbc.query("""
            WITH picked AS (
                SELECT id
                FROM execution_outbox
                WHERE published_at IS NULL
                  AND (
                    claimed_until IS NULL
                    OR claimed_until < CURRENT_TIMESTAMP
                  )
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE execution_outbox e
            SET claim_token = ?,
                claimed_until =
                    CURRENT_TIMESTAMP + INTERVAL '2 minutes'
            FROM picked
            WHERE e.id = picked.id
            RETURNING e.id, e.execution_id
            """,
            (rs, rowNum) -> new PendingOutbox(
                rs.getLong("id"),
                rs.getLong("execution_id")
            ),
            batchSize,
            claimToken
        );
    }

    @Transactional
    public boolean markPublished(
            PendingOutbox event,
            String claimToken) {

        int updated = jdbc.update("""
            UPDATE execution_outbox
            SET published_at = CURRENT_TIMESTAMP,
                claim_token = NULL,
                claimed_until = NULL
            WHERE id = ?
              AND claim_token = ?
              AND published_at IS NULL
            """,
            event.outboxId(),
            claimToken
        );

        if (updated == 0) {
            return false;
        }

        // Only advance RECEIVED executions.
        // Never overwrite RUNNING, SUCCEEDED or FAILED.
        jdbc.update("""
            UPDATE workflow_executions
            SET status = 'QUEUED'
            WHERE id = ?
              AND status = 'RECEIVED'
            """,
            event.executionId()
        );

        return true;
    }

    @Transactional
    public void releaseClaim(
            long outboxId,
            String claimToken) {

        jdbc.update("""
            UPDATE execution_outbox
            SET claim_token = NULL,
                claimed_until = NULL
            WHERE id = ?
              AND claim_token = ?
              AND published_at IS NULL
            """,
            outboxId,
            claimToken
        );
    }
}