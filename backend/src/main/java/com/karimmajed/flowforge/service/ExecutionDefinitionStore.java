package com.karimmajed.flowforge.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExecutionDefinitionStore {

    private final JdbcTemplate jdbc;

    public ExecutionDefinitionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void freeze(long executionId, long workflowId) {
        jdbc.queryForObject(
                "SELECT id FROM workflows WHERE id = ? FOR UPDATE",
                Long.class,
                workflowId);

        Boolean frozen = jdbc.queryForObject(
                """
                SELECT definition_frozen
                FROM workflow_executions
                WHERE id = ?
                """,
                Boolean.class,
                executionId);

        if (Boolean.TRUE.equals(frozen)) {
            return;
        }

        jdbc.update("""
                INSERT INTO execution_step_definitions (
                    execution_id,
                    workflow_step_id,
                    name,
                    step_type,
                    position,
                    configuration
                )
                SELECT ?, id, name, step_type, position, configuration
                FROM workflow_steps
                WHERE workflow_id = ?
                """,
                executionId,
                workflowId);

        int updated = jdbc.update("""
                UPDATE workflow_executions
                SET definition_frozen = TRUE
                WHERE id = ?
                  AND definition_frozen = FALSE
                """,
                executionId);

        if (updated != 1) {
            throw new IllegalStateException(
                    "Could not freeze execution " + executionId);
        }
    }
}
