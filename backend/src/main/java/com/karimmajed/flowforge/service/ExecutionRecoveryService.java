package com.karimmajed.flowforge.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class ExecutionRecoveryService {

    private static final int MAX_RECOVERIES = 3;
    private static final int COOLDOWN_MINUTES = 10;

    private final JdbcTemplate jdbc;

    public ExecutionRecoveryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record RecoveryCandidate(
        String status,
        int attempts,
        OffsetDateTime lastRequestedAt
    ) {}

    @Transactional
    public void requestRecovery(long executionId) {

        // Lock the execution row to serialize
        // concurrent recovery requests.
        List<RecoveryCandidate> matches = jdbc.query("""
            SELECT status,
                   recovery_attempts,
                   last_recovery_requested_at
            FROM workflow_executions
            WHERE id = ?
            FOR UPDATE
            """,
            (rs, rowNum) -> new RecoveryCandidate(
                rs.getString("status"),
                rs.getInt("recovery_attempts"),
                rs.getObject(
                    "last_recovery_requested_at",
                    OffsetDateTime.class
                )
            ),
            executionId
        );

        if (matches.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Execution not found"
            );
        }

        RecoveryCandidate candidate = matches.get(0);

        // Never automatically reset completed,
        // failed, or running executions.
        if (!candidate.status().equals("QUEUED")
                && !candidate.status().equals("RECEIVED")) {

            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Execution cannot be recovered from status: "
                    + candidate.status()
            );
        }

        if (candidate.attempts() >= MAX_RECOVERIES) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Maximum recovery attempts reached"
            );
        }

        OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

        if (candidate.lastRequestedAt() != null
                && candidate.lastRequestedAt().isAfter(
                    now.minusMinutes(COOLDOWN_MINUTES)
                )) {

            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A recovery was requested recently"
            );
        }

        // Don't queue a second outbox event while
        // another is still waiting to be published.
        Integer pending = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM execution_outbox
            WHERE execution_id = ?
              AND published_at IS NULL
            """,
            Integer.class,
            executionId
        );

        if (pending != null && pending > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Execution already has a pending outbox event"
            );
        }

        jdbc.update("""
            UPDATE workflow_executions
            SET recovery_attempts = recovery_attempts + 1,
                last_recovery_requested_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """,
            executionId
        );

        // Reuse the existing reliable publisher.
        jdbc.update("""
            INSERT INTO execution_outbox (execution_id)
            VALUES (?)
            """,
            executionId
        );
    }
}