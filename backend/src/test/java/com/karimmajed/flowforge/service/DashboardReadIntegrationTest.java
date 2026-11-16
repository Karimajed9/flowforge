package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.DashboardOverviewResponse;
import com.karimmajed.flowforge.dto.RecentExecutionResponse;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.config.import=optional:classpath:application.properties",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
        DashboardReadService.class,
        WorkflowService.class,
        WebhookTokenService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class DashboardReadIntegrationTest {

    private static final String PAYLOAD_SECRET = "payload-secret-value";
    private static final String IDEMPOTENCY_SECRET = "idem-secret-value";
    private static final String WEBHOOK_SECRET = "webhook-hash-secret";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DashboardReadService reads;

    @Autowired
    private WorkflowService workflows;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(postgres.getMappedPort(5432)).isNotEqualTo(5433);
        assertThat(url).contains(":" + postgres.getMappedPort(5432));

        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    void emptyDatabaseHasZeroCountsAndNoSuccessRate() {
        DashboardOverviewResponse overview = reads.overview();

        assertThat(overview.totalWorkflows()).isZero();
        assertThat(overview.activeWorkflows()).isZero();
        assertThat(overview.totalExecutions()).isZero();
        assertThat(overview.succeededExecutions()).isZero();
        assertThat(overview.failedExecutions()).isZero();
        assertThat(overview.successRate()).isNull();
        assertThat(overview.windowDays())
                .isEqualTo(DashboardReadService.SUCCESS_RATE_WINDOW_DAYS);
        assertThat(reads.recentExecutions(0, 20).getContent()).isEmpty();
        assertThat(reads.recentExecutions(0, 20).getTotalElements()).isZero();
    }

    @Test
    void overviewCountsTheSevenDayWindowWithoutLoadingPayloads() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long active = insertWorkflow("Onboarding", true, now.minusDays(2));
        insertWorkflow("Paused", false, now.minusDays(3));

        long recentSuccess = insertExecution(
                active, "SUCCEEDED", now.minusDays(1), null);
        long recentFailure = insertExecution(
                active, "FAILED", now.minusHours(3), "downstream rejected the request");
        insertExecution(active, "SUCCEEDED", now.minusDays(8), null);
        long running = insertExecution(active, "RUNNING", now.minusHours(1), null);
        long received = insertExecution(active, "RECEIVED", now.minusMinutes(5), null);

        DashboardOverviewResponse overview = reads.overview();

        assertThat(overview.totalWorkflows()).isEqualTo(2);
        assertThat(overview.activeWorkflows()).isEqualTo(1);
        assertThat(overview.totalExecutions()).isEqualTo(5);
        assertThat(overview.succeededExecutions()).isEqualTo(1);
        assertThat(overview.failedExecutions()).isEqualTo(1);
        assertThat(overview.successRate()).isEqualTo(0.5);
        assertThat(overview.windowDays()).isEqualTo(7);
        assertThat(overview.toString()).doesNotContain(
                PAYLOAD_SECRET, IDEMPOTENCY_SECRET, WEBHOOK_SECRET);

        Page<RecentExecutionResponse> first = reads.recentExecutions(0, 2);
        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getContent()).extracting(RecentExecutionResponse::id)
                .containsExactly(received, running);
        assertThat(first.getContent().get(0).workflowName()).isEqualTo("Onboarding");
        assertThat(first.toString()).doesNotContain(
                PAYLOAD_SECRET, IDEMPOTENCY_SECRET, WEBHOOK_SECRET);

        Page<RecentExecutionResponse> second = reads.recentExecutions(1, 2);
        assertThat(second.getNumber()).isEqualTo(1);
        assertThat(second.getContent()).extracting(RecentExecutionResponse::id)
                .containsExactly(recentFailure, recentSuccess);
        RecentExecutionResponse failed = second.getContent().get(0);
        assertThat(failed.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("downstream rejected the request");
        assertThat(second.toString()).doesNotContain(
                PAYLOAD_SECRET, IDEMPOTENCY_SECRET, WEBHOOK_SECRET);
    }

    @Test
    void inProgressExecutionsDoNotCreateASuccessRate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long workflowId = insertWorkflow("Onboarding", true, now);
        insertExecution(workflowId, "QUEUED", now.minusMinutes(2), null);
        insertExecution(workflowId, "RUNNING", now.minusMinutes(1), null);

        DashboardOverviewResponse overview = reads.overview();

        assertThat(overview.totalExecutions()).isEqualTo(2);
        assertThat(overview.succeededExecutions()).isZero();
        assertThat(overview.failedExecutions()).isZero();
        assertThat(overview.successRate()).isNull();
    }

    @Test
    void workflowListIsPagedNewestFirst() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertWorkflow("Oldest", true, now.minusDays(3));
        long middle = insertWorkflow("Middle", false, now.minusDays(2));
        long newest = insertWorkflow("Newest", true, now.minusDays(1));

        Page<WorkflowResponse> first = workflows.getPage(0, 2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(WorkflowResponse::id)
                .containsExactly(newest, middle);
        assertThat(first.toString()).doesNotContain(WEBHOOK_SECRET);

        Page<WorkflowResponse> second = workflows.getPage(1, 2);
        assertThat(second.getContent()).extracting(WorkflowResponse::name)
                .containsExactly("Oldest");
    }

    @Test
    void pageLimitsAreRejected() {
        ResponseStatusException workflowsTooLarge = assertThrows(
                ResponseStatusException.class,
                () -> workflows.getPage(0, 101));
        ResponseStatusException executionsNegative = assertThrows(
                ResponseStatusException.class,
                () -> reads.recentExecutions(-1, 20));

        assertThat(workflowsTooLarge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(executionsNegative.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(workflowsTooLarge.getReason())
                .isEqualTo("Page must be >= 0 and size must be between 1 and 100");
    }

    private long insertWorkflow(String name, boolean active, OffsetDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO workflows (
                    name, description, active, created_at, webhook_token_hash
                )
                VALUES (?, NULL, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                name,
                active,
                createdAt,
                WEBHOOK_SECRET + "-" + UUID.randomUUID());
    }

    private long insertExecution(
            long workflowId,
            String status,
            OffsetDateTime createdAt,
            String failureReason) {

        return jdbc.queryForObject("""
                INSERT INTO workflow_executions (
                    workflow_id, status, payload, created_at, idempotency_key, failure_reason
                )
                VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                workflowId,
                status,
                "{\"token\":\"" + PAYLOAD_SECRET + "\"}",
                createdAt,
                IDEMPOTENCY_SECRET + "-" + UUID.randomUUID(),
                failureReason);
    }
}
