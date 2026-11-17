package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.DashboardOverviewResponse;
import com.karimmajed.flowforge.dto.RecentExecutionResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.security.ResourceOwner;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class DashboardReadService {

    public static final int SUCCESS_RATE_WINDOW_DAYS = 7;

    private final JdbcTemplate jdbc;

    public DashboardReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DashboardOverviewResponse overview(ResourceOwner owner) {
        OffsetDateTime windowStart = OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(SUCCESS_RATE_WINDOW_DAYS);

        long[] workflows = jdbc.queryForObject("""
                SELECT
                    COUNT(*) AS total_workflows,
                    COUNT(*) FILTER (WHERE active) AS active_workflows
                FROM workflows
                WHERE owner_subject = ?
                  AND owner_issuer = ?
                """,
                (rs, row) -> new long[] {
                    rs.getLong("total_workflows"),
                    rs.getLong("active_workflows")
                },
                owner.subject(),
                owner.issuer());

        long[] executions = jdbc.queryForObject("""
                SELECT
                    COUNT(*) AS total_executions,
                    COUNT(*) FILTER (
                        WHERE e.status = 'SUCCEEDED' AND e.created_at >= ?
                    ) AS succeeded_executions,
                    COUNT(*) FILTER (
                        WHERE e.status = 'FAILED' AND e.created_at >= ?
                    ) AS failed_executions
                FROM workflow_executions e
                JOIN workflows w ON w.id = e.workflow_id
                WHERE w.owner_subject = ?
                  AND w.owner_issuer = ?
                """,
                (rs, row) -> new long[] {
                    rs.getLong("total_executions"),
                    rs.getLong("succeeded_executions"),
                    rs.getLong("failed_executions")
                },
                windowStart,
                windowStart,
                owner.subject(),
                owner.issuer());

        if (workflows == null || executions == null) {
            throw new IllegalStateException("Dashboard counts did not return a row");
        }

        long succeeded = executions[1];
        long failed = executions[2];
        long finished = succeeded + failed;
        Double successRate = finished == 0
                ? null
                : (double) succeeded / (double) finished;

        return new DashboardOverviewResponse(
                workflows[0],
                workflows[1],
                executions[0],
                succeeded,
                failed,
                successRate,
                SUCCESS_RATE_WINDOW_DAYS
        );
    }

    @Transactional(readOnly = true)
    public Page<RecentExecutionResponse> recentExecutions(
            int page,
            int size,
            ResourceOwner owner) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be >= 0 and size must be between 1 and 100"
            );
        }

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM workflow_executions e
                JOIN workflows w ON w.id = e.workflow_id
                WHERE w.owner_subject = ?
                  AND w.owner_issuer = ?
                """,
                Long.class,
                owner.subject(),
                owner.issuer());
        long offset = (long) page * size;
        List<RecentExecutionResponse> rows = jdbc.query("""
                SELECT
                    e.id,
                    e.workflow_id,
                    w.name AS workflow_name,
                    e.status,
                    e.created_at,
                    e.failure_reason
                FROM workflow_executions e
                JOIN workflows w ON w.id = e.workflow_id
                WHERE w.owner_subject = ?
                  AND w.owner_issuer = ?
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT ? OFFSET ?
                """,
                this::mapRecentExecution,
                owner.subject(),
                owner.issuer(),
                size,
                offset);

        return new PageImpl<>(
                rows,
                PageRequest.of(page, size),
                total == null ? 0 : total
        );
    }

    private RecentExecutionResponse mapRecentExecution(ResultSet rs, int row)
            throws SQLException {
        return new RecentExecutionResponse(
                rs.getLong("id"),
                rs.getLong("workflow_id"),
                rs.getString("workflow_name"),
                ExecutionStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("failure_reason")
        );
    }
}
