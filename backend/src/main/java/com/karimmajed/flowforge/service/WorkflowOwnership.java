package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.security.ResourceOwner;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recovery and abandonment stay behind the recovery key. The key does
 * not grant access to another user's execution, and a missing owner is
 * the same answer as a missing execution.
 */
@Service
public class WorkflowOwnership {

    private final JdbcTemplate jdbc;

    public WorkflowOwnership(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireExecution(long executionId, ResourceOwner owner) {
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
    }
}
