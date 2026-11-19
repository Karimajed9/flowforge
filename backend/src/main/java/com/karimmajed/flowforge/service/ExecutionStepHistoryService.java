package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.security.ResourceOwner;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ExecutionStepHistoryService {

    private final JdbcTemplate jdbc;

    public ExecutionStepHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ExecutionStepResponse> getSteps(Long executionId, ResourceOwner owner) {
        Integer found = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM workflow_executions e
                JOIN workflows w ON w.id = e.workflow_id
                WHERE e.id = ?
                  AND w.owner_subject = ?
                  AND w.owner_issuer = ?
                """,
                Integer.class,
                executionId,
                owner.subject(),
                owner.issuer());

        if (found == null || found == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Execution not found");
        }

        Boolean frozen = jdbc.queryForObject(
                """
                SELECT definition_frozen
                FROM workflow_executions
                WHERE id = ?
                """,
                Boolean.class,
                executionId);

        String steps = Boolean.TRUE.equals(frozen)
                ? """
                SELECT d.workflow_step_id AS step_id,
                       d.name,
                       d.step_type,
                       d.position,
                       c.status,
                       c.attempt_number,
                       c.failure_reason,
                       c.created_at,
                       c.updated_at,
                       c.lease_until,
                       c.branch_taken
                FROM workflow_executions e
                JOIN execution_step_definitions d
                  ON d.execution_id = e.id
                LEFT JOIN execution_step_checkpoints c
                  ON c.execution_id = e.id
                 AND c.workflow_step_id = d.workflow_step_id
                JOIN workflows w ON w.id = e.workflow_id
                WHERE e.id = ?
                  AND w.owner_subject = ?
                  AND w.owner_issuer = ?
                ORDER BY d.position ASC, d.workflow_step_id ASC
                """
                : """
                SELECT s.id AS step_id,
                       s.name,
                       s.step_type,
                       s.position,
                       c.status,
                       c.attempt_number,
                       c.failure_reason,
                       c.created_at,
                       c.updated_at,
                       c.lease_until,
                       c.branch_taken
                FROM workflow_executions e
                JOIN workflow_steps s
                  ON s.workflow_id = e.workflow_id
                LEFT JOIN execution_step_checkpoints c
                  ON c.execution_id = e.id
                 AND c.workflow_step_id = s.id
                JOIN workflows w ON w.id = e.workflow_id
                WHERE e.id = ?
                  AND w.owner_subject = ?
                  AND w.owner_issuer = ?
                ORDER BY s.position ASC, s.id ASC
                """;

        return jdbc.query(
                steps,
                (rs, rowNum) -> mapRow(rs),
                executionId,
                owner.subject(),
                owner.issuer());
    }

    static ExecutionStepResponse mapRow(ResultSet rs) throws SQLException {
        String status = rs.getString("status");

        if (status == null) {
            return new ExecutionStepResponse(
                    rs.getLong("step_id"),
                    rs.getString("name"),
                    StepType.valueOf(rs.getString("step_type")),
                    rs.getInt("position"),
                    ExecutionStepStatus.NOT_STARTED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        return new ExecutionStepResponse(
                rs.getLong("step_id"),
                rs.getString("name"),
                StepType.valueOf(rs.getString("step_type")),
                rs.getInt("position"),
                ExecutionStepStatus.valueOf(status),
                rs.getObject("attempt_number", Integer.class),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("lease_until", OffsetDateTime.class),
                booleanOrNull(rs.getObject("branch_taken")));
    }

    private static Boolean booleanOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean taken) {
            return taken;
        }
        return Boolean.valueOf(value.toString());
    }
}
