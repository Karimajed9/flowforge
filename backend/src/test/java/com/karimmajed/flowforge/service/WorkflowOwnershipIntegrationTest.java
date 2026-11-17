package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.controller.DashboardController;
import com.karimmajed.flowforge.controller.ExecutionRecoveryController;
import com.karimmajed.flowforge.controller.WorkflowController;
import com.karimmajed.flowforge.controller.WorkflowExecutionController;
import com.karimmajed.flowforge.controller.WorkflowStepController;
import com.karimmajed.flowforge.dto.AbandonRunningStepRequest;
import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.CreateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.DashboardOverviewResponse;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.WebhookTokenResponse;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.security.CurrentCaller;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.validation.StepConfigurationValidator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.config.import=optional:classpath:application.properties",
        "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({
        WorkflowService.class,
        WorkflowStepService.class,
        WorkflowExecutionService.class,
        DashboardReadService.class,
        ExecutionStepHistoryService.class,
        WebhookTokenService.class,
        WebhookService.class,
        StepConfigurationValidator.class,
        ExecutionRecoveryService.class,
        ExecutionAbandonmentService.class,
        WorkflowOwnership.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkflowOwnershipIntegrationTest {

    private static final String ISSUER = "http://auth.test/realms/flowforge";
    private static final ResourceOwner USER_A = new ResourceOwner("user-a", ISSUER);
    private static final ResourceOwner USER_B = new ResourceOwner("user-b", ISSUER);
    private static final ResourceOwner OTHER_ISSUER =
            new ResourceOwner("user-a", "http://other.example/realms/flowforge");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkflowService workflows;

    @Autowired
    private WorkflowStepService steps;

    @Autowired
    private WorkflowExecutionService executions;

    @Autowired
    private ExecutionStepHistoryService history;

    @Autowired
    private DashboardReadService reads;

    @Autowired
    private WebhookService webhooks;

    @Autowired
    private WebhookTokenService tokens;

    @Autowired
    private ExecutionRecoveryService recovery;

    @Autowired
    private ExecutionAbandonmentService abandonment;

    @Autowired
    private WorkflowOwnership ownership;

    private ExecutionRecoveryController adminApi;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(postgres.getMappedPort(5432)).isNotEqualTo(5433);
        assertThat(url).contains(":" + postgres.getMappedPort(5432));
        assertThat(url).doesNotContain(":5672");

        jdbc.update("DELETE FROM execution_admin_audit");
        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
        SecurityContextHolder.clearContext();

        adminApi = new ExecutionRecoveryController(
                recovery,
                abandonment,
                ownership,
                new CurrentCaller(),
                "test-recovery-key");
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createdWorkflowBelongsToTheCallerAndIsHiddenFromEveryoneElse() {
        var created = workflows.create(
                new CreateWorkflowRequest("Alpha", "owner-subject:user-b"),
                USER_A);

        assertThat(ownerOf(created.id())).isEqualTo(USER_A);
        assertThat(workflows.getById(created.id(), USER_A).name()).isEqualTo("Alpha");
        assertThat(workflows.getById(created.id(), USER_A).toString())
                .doesNotContain("user-a", ISSUER);

        assertNotFound(
                () -> workflows.getById(created.id(), USER_B),
                "Workflow not found");
        assertNotFound(
                () -> workflows.getById(created.id(), OTHER_ISSUER),
                "Workflow not found");
        assertNotFound(
                () -> workflows.updateStatus(
                        created.id(),
                        new UpdateWorkflowStatusRequest(true),
                        USER_B),
                "Workflow not found");
        assertThat(workflows.getById(created.id(), USER_A).active()).isFalse();

        assertNotFound(
                () -> steps.create(created.id(), validateStep(), USER_B),
                "Workflow not found");
        steps.create(created.id(), validateStep(), USER_A);
        assertThat(steps.getAll(created.id(), USER_A)).hasSize(1);
        assertNotFound(
                () -> steps.getAll(created.id(), USER_B),
                "Workflow not found");

        assertNotFound(
                () -> workflows.rotateWebhookToken(created.id(), USER_B),
                "Workflow not found");
        assertThat(tokenHash(created.id())).isNull();

        WebhookTokenResponse minted = workflows.rotateWebhookToken(created.id(), USER_A);
        String hash = tokenHash(created.id());
        assertThat(minted.token()).isNotBlank();
        assertThat(hash).isEqualTo(tokens.hashToken(minted.token()));

        assertNotFound(
                () -> workflows.rotateWebhookToken(created.id(), USER_B),
                "Workflow not found");
        assertThat(tokenHash(created.id())).isEqualTo(hash);

        authenticate("user-b");
        assertNotFound(
                () -> workflows.getById(created.id(), new CurrentCaller().require()),
                "Workflow not found");
    }

    @Test
    void executionsAndStepHistoryFollowTheWorkflowOwner() {
        var workflow = workflows.create(new CreateWorkflowRequest("Alpha", null), USER_A);
        workflows.updateStatus(workflow.id(), new UpdateWorkflowStatusRequest(true), USER_A);
        steps.create(workflow.id(), validateStep(), USER_A);
        String token = workflows.rotateWebhookToken(workflow.id(), USER_A).token();

        SecurityContextHolder.clearContext();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        var triggered = webhooks.trigger(token, "event-" + UUID.randomUUID(), Map.of("amount", 1));

        assertThat(triggered.replayed()).isFalse();
        assertThat(executions.getById(triggered.response().id(), USER_A).workflowId())
                .isEqualTo(workflow.id());
        assertThat(history.getSteps(triggered.response().id(), USER_A)).hasSize(1);
        assertThat(executions.getByWorkflow(workflow.id(), 0, 20, USER_A).getContent())
                .extracting(row -> row.id())
                .containsExactly(triggered.response().id());

        assertNotFound(
                () -> executions.getById(triggered.response().id(), USER_B),
                "Execution not found");
        assertNotFound(
                () -> history.getSteps(triggered.response().id(), USER_B),
                "Execution not found");
        assertNotFound(
                () -> executions.getByWorkflow(workflow.id(), 0, 20, USER_B),
                "Workflow not found");
    }

    @Test
    void listsAndDashboardCountsExcludeOtherUsersAndLegacyRows() {
        var alpha = workflows.create(new CreateWorkflowRequest("Alpha", null), USER_A);
        workflows.updateStatus(alpha.id(), new UpdateWorkflowStatusRequest(true), USER_A);
        String token = workflows.rotateWebhookToken(alpha.id(), USER_A).token();
        SecurityContextHolder.clearContext();
        long alphaExecution = webhooks.trigger(
                token, "event-" + UUID.randomUUID(), Map.of("amount", 1)).response().id();

        var beta = workflows.create(new CreateWorkflowRequest("Beta", null), USER_B);
        long legacyWorkflow = insertWorkflow("Legacy", true, null, null);
        long legacyExecution = insertExecution(legacyWorkflow, "SUCCEEDED");

        assertThat(workflows.getPage(0, 20, USER_A).getContent())
                .extracting(row -> row.name())
                .containsExactly("Alpha");
        assertThat(workflows.getPage(0, 20, USER_B).getContent())
                .extracting(row -> row.name())
                .containsExactly("Beta");

        assertThat(reads.recentExecutions(0, 20, USER_A).getContent())
                .extracting(row -> row.id())
                .containsExactly(alphaExecution);
        assertThat(reads.recentExecutions(0, 20, USER_B).getTotalElements()).isZero();

        DashboardOverviewResponse alphaOverview = reads.overview(USER_A);
        assertThat(alphaOverview.totalWorkflows()).isEqualTo(1);
        assertThat(alphaOverview.activeWorkflows()).isEqualTo(1);
        assertThat(alphaOverview.totalExecutions()).isEqualTo(1);
        assertThat(alphaOverview.succeededExecutions()).isZero();
        assertThat(alphaOverview.successRate()).isNull();

        DashboardOverviewResponse betaOverview = reads.overview(USER_B);
        assertThat(betaOverview.totalWorkflows()).isEqualTo(1);
        assertThat(betaOverview.totalExecutions()).isZero();
        assertThat(betaOverview.succeededExecutions()).isZero();

        assertNotFound(() -> workflows.getById(legacyWorkflow, USER_A), "Workflow not found");
        assertNotFound(() -> workflows.getById(legacyWorkflow, USER_B), "Workflow not found");
        assertNotFound(() -> executions.getById(legacyExecution, USER_A), "Execution not found");
        assertNotFound(() -> executions.getById(legacyExecution, USER_B), "Execution not found");
        assertThat(beta.id()).isNotEqualTo(legacyWorkflow);
    }

    @Test
    void validWebhookTokenTriggersWithoutAJwtIncludingALegacyWorkflow() {
        String legacyToken = "legacy-plain-token";
        long legacyWorkflow = insertWorkflow(
                "Legacy",
                true,
                tokens.hashToken(legacyToken),
                null);
        SecurityContextHolder.clearContext();

        var triggered = webhooks.trigger(
                legacyToken,
                "legacy-" + UUID.randomUUID(),
                Map.of("source", "webhook"));

        assertThat(triggered.replayed()).isFalse();
        assertThat(triggered.response().workflowId()).isEqualTo(legacyWorkflow);
        assertNotFound(
                () -> executions.getById(triggered.response().id(), USER_A),
                "Execution not found");
        assertNotFound(
                () -> executions.getById(triggered.response().id(), USER_B),
                "Execution not found");
    }

    @Test
    void adminRecoveryAndAbandonmentDoNotBypassOwnership() {
        var workflow = workflows.create(new CreateWorkflowRequest("Alpha", null), USER_A);
        long executionId = insertExecution(workflow.id(), "QUEUED");
        long legacyWorkflow = insertWorkflow("Legacy", true, null, null);
        long legacyExecution = insertExecution(legacyWorkflow, "QUEUED");

        authenticate("user-b");
        assertStatus(
                () -> adminApi.recover(executionId, "wrong-key"),
                HttpStatus.FORBIDDEN,
                "Invalid recovery credentials");
        assertStatus(
                () -> adminApi.abandon(
                        executionId,
                        1L,
                        null,
                        new AbandonRunningStepRequest(1, "worker process is gone")),
                HttpStatus.FORBIDDEN,
                "Invalid recovery credentials");
        assertThat(recoveryAttempts(executionId)).isZero();
        assertThat(auditCount(executionId)).isZero();

        authenticate("user-a");
        assertStatus(
                () -> adminApi.recover(executionId, "wrong-key"),
                HttpStatus.FORBIDDEN,
                "Invalid recovery credentials");
        assertThat(recoveryAttempts(executionId)).isZero();

        authenticate("user-b");
        assertNotFound(() -> adminApi.recover(executionId, "test-recovery-key"), "Execution not found");
        assertNotFound(
                () -> adminApi.abandon(
                        executionId,
                        1L,
                        "test-recovery-key",
                        new AbandonRunningStepRequest(1, "worker process is gone")),
                "Execution not found");
        assertThat(recoveryAttempts(executionId)).isZero();
        assertThat(statusOf(executionId)).isEqualTo("QUEUED");
        assertThat(auditCount(executionId)).isZero();

        authenticate("user-a");
        assertNotFound(
                () -> adminApi.recover(legacyExecution, "test-recovery-key"),
                "Execution not found");
        assertThat(recoveryAttempts(legacyExecution)).isZero();

        var recovered = adminApi.recover(executionId, "test-recovery-key");
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(recoveryAttempts(executionId)).isEqualTo(1);
    }

    @Test
    void listingPagesCountOnlyTheAuthenticatedUsersWorkflows() {
        authenticate("user-a", ISSUER, "flowforge.user");
        WorkflowController api = workflowsApi();
        api.create(new CreateWorkflowRequest("A oldest", null));
        api.create(new CreateWorkflowRequest("A middle", null));
        api.create(new CreateWorkflowRequest("A newest", null));

        authenticate("user-b", ISSUER, "flowforge.user");
        api.create(new CreateWorkflowRequest("B first", null));
        api.create(new CreateWorkflowRequest("B second", null));
        insertWorkflow("Legacy", false, null, null);

        authenticate("user-a", ISSUER, "flowforge.user");
        var first = api.getAll(0, 2);
        var second = api.getAll(1, 2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).extracting(row -> row.name())
                .containsExactly("A newest", "A middle");
        assertThat(second.getContent()).extracting(row -> row.name())
                .containsExactly("A oldest");
        assertThat(first.toString()).doesNotContain("B first", "B second", "Legacy");
        assertThat(second.toString()).doesNotContain("B first", "B second", "Legacy");

        authenticate("user-b", ISSUER, "flowforge.user");
        var beta = api.getAll(0, 2);
        assertThat(beta.getTotalElements()).isEqualTo(2);
        assertThat(beta.getContent()).extracting(row -> row.name())
                .containsExactly("B second", "B first");
        assertThat(beta.toString()).doesNotContain("A newest", "A oldest", "Legacy");
    }

    @Test
    void dashboardPagesAndCountsStayInsideTheCaller() {
        var alpha = workflows.create(new CreateWorkflowRequest("Alpha", null), USER_A);
        var beta = workflows.create(new CreateWorkflowRequest("Beta", null), USER_B);
        long legacyWorkflow = insertWorkflow("Legacy", true, null, null);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long alphaOlder = insertExecution(alpha.id(), "RECEIVED", now.minusHours(2));
        long alphaNewer = insertExecution(alpha.id(), "FAILED", now.minusHours(1));
        long betaFailed = insertExecution(beta.id(), "FAILED", now);
        insertExecution(legacyWorkflow, "SUCCEEDED", now.plusMinutes(1));

        authenticate("user-a", ISSUER, "flowforge.user");
        WorkflowExecutionController executionsApi = executionsApi();
        DashboardController dashboardApi = new DashboardController(reads, new CurrentCaller());

        var newest = executionsApi.recentExecutions(0, 1);
        var older = executionsApi.recentExecutions(1, 1);
        assertThat(newest.getTotalElements()).isEqualTo(2);
        assertThat(newest.getContent()).extracting(row -> row.id())
                .containsExactly(alphaNewer);
        assertThat(newest.getContent()).extracting(row -> row.workflowName())
                .containsExactly("Alpha");
        assertThat(older.getContent()).extracting(row -> row.id())
                .containsExactly(alphaOlder);
        assertThat(newest.toString()).doesNotContain("Beta", "Legacy");

        DashboardOverviewResponse overview = dashboardApi.overview();
        assertThat(overview.totalWorkflows()).isEqualTo(1);
        assertThat(overview.totalExecutions()).isEqualTo(2);
        assertThat(overview.failedExecutions()).isEqualTo(1);
        assertThat(overview.succeededExecutions()).isZero();
        assertThat(overview.successRate()).isEqualTo(0.0);

        authenticate("user-b", ISSUER, "flowforge.user");
        var betaRecent = executionsApi.recentExecutions(0, 20);
        assertThat(betaRecent.getTotalElements()).isEqualTo(1);
        assertThat(betaRecent.getContent()).extracting(row -> row.id())
                .containsExactly(betaFailed);
        DashboardOverviewResponse betaOverview = dashboardApi.overview();
        assertThat(betaOverview.totalWorkflows()).isEqualTo(1);
        assertThat(betaOverview.totalExecutions()).isEqualTo(1);
        assertThat(betaOverview.failedExecutions()).isEqualTo(1);
        assertThat(betaOverview.succeededExecutions()).isZero();
    }

    @Test
    void administratorDoesNotBypassNormalUserEndpoints() {
        var created = workflows.create(new CreateWorkflowRequest("Alpha", null), USER_A);
        steps.create(created.id(), validateStep(), USER_A);
        long executionId = insertExecution(created.id(), "RECEIVED");

        authenticate("user-b", ISSUER, "flowforge.admin");
        WorkflowController workflowsApi = workflowsApi();
        WorkflowStepController stepsApi = new WorkflowStepController(steps, new CurrentCaller());
        WorkflowExecutionController executionsApi = executionsApi();
        DashboardController dashboardApi = new DashboardController(reads, new CurrentCaller());

        assertThat(workflowsApi.getAll(0, 20).getTotalElements()).isZero();
        assertNotFound(() -> workflowsApi.getById(created.id()), "Workflow not found");
        assertNotFound(
                () -> workflowsApi.updateStatus(
                        created.id(),
                        new UpdateWorkflowStatusRequest(true)),
                "Workflow not found");
        assertThat(workflows.getById(created.id(), USER_A).active()).isFalse();
        assertNotFound(() -> stepsApi.getAll(created.id()), "Workflow not found");
        assertNotFound(() -> stepsApi.create(created.id(), validateStep()), "Workflow not found");
        assertNotFound(() -> workflowsApi.rotateWebhookToken(created.id()), "Workflow not found");
        assertThat(tokenHash(created.id())).isNull();
        assertNotFound(() -> executionsApi.getExecution(executionId), "Execution not found");
        assertNotFound(() -> executionsApi.getExecutionSteps(executionId), "Execution not found");
        assertNotFound(
                () -> executionsApi.getWorkflowExecutions(created.id(), 0, 20),
                "Workflow not found");
        assertThat(executionsApi.recentExecutions(0, 20).getTotalElements()).isZero();
        assertThat(dashboardApi.overview().totalWorkflows()).isZero();
        assertThat(dashboardApi.overview().totalExecutions()).isZero();

        authenticate("user-a", ISSUER, "flowforge.admin");
        assertThat(workflowsApi.getById(created.id()).name()).isEqualTo("Alpha");
        assertThat(executionsApi.getExecution(executionId).workflowId()).isEqualTo(created.id());
    }

    @Test
    void legacyWorkflowsStayClosedToUserManagement() {
        long legacyWorkflow = insertWorkflow("Legacy", true, null, null);
        long legacyExecution = insertExecution(legacyWorkflow, "QUEUED");
        workflows.create(new CreateWorkflowRequest("Alpha", null), USER_A);

        authenticate("user-a", ISSUER, "flowforge.user");
        WorkflowController workflowsApi = workflowsApi();
        WorkflowStepController stepsApi = new WorkflowStepController(steps, new CurrentCaller());
        WorkflowExecutionController executionsApi = executionsApi();

        assertThat(workflowsApi.getAll(0, 20).getTotalElements()).isEqualTo(1);
        assertThat(workflowsApi.getAll(0, 20).getContent())
                .extracting(row -> row.name())
                .containsExactly("Alpha");
        assertNotFound(() -> workflowsApi.getById(legacyWorkflow), "Workflow not found");
        assertNotFound(
                () -> workflowsApi.updateStatus(
                        legacyWorkflow,
                        new UpdateWorkflowStatusRequest(false)),
                "Workflow not found");
        assertNotFound(() -> stepsApi.create(legacyWorkflow, validateStep()), "Workflow not found");
        assertNotFound(() -> stepsApi.getAll(legacyWorkflow), "Workflow not found");
        assertNotFound(() -> workflowsApi.rotateWebhookToken(legacyWorkflow), "Workflow not found");
        assertNotFound(
                () -> executionsApi.getWorkflowExecutions(legacyWorkflow, 0, 20),
                "Workflow not found");
        assertNotFound(() -> executionsApi.getExecution(legacyExecution), "Execution not found");
        assertNotFound(() -> executionsApi.getExecutionSteps(legacyExecution), "Execution not found");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ?",
                Integer.class,
                legacyWorkflow)).isZero();
        assertThat(tokenHash(legacyWorkflow)).isNull();
    }

    @Test
    void clientSuppliedOwnerIsIgnoredAndIssuersDoNotCollide() throws Exception {
        CreateWorkflowRequest supplied = JsonMapper.builder().build().readValue("""
                {
                  "name": "From token",
                  "description": "notes",
                  "ownerSubject": "user-b",
                  "ownerIssuer": "http://evil.example/realms/other",
                  "ownerId": 99
                }
                """, CreateWorkflowRequest.class);
        assertThat(supplied.name()).isEqualTo("From token");
        assertThat(supplied.description()).isEqualTo("notes");

        authenticate("user-a", ISSUER, "flowforge.user");
        var created = workflowsApi().create(supplied).getBody();
        assertThat(created).isNotNull();
        assertThat(ownerOf(created.id())).isEqualTo(USER_A);
        assertThat(created.toString()).doesNotContain("user-b", "evil.example", "99");

        authenticate("user-a", OTHER_ISSUER.issuer(), "flowforge.user");
        var other = workflowsApi().create(new CreateWorkflowRequest("Other issuer", null)).getBody();
        assertThat(other).isNotNull();
        assertThat(ownerOf(other.id())).isEqualTo(OTHER_ISSUER);

        assertThat(workflowsApi().getById(other.id()).name()).isEqualTo("Other issuer");
        assertNotFound(() -> workflowsApi().getById(created.id()), "Workflow not found");

        authenticate("user-a", ISSUER, "flowforge.user");
        assertThat(workflowsApi().getById(created.id()).name()).isEqualTo("From token");
        assertNotFound(() -> workflowsApi().getById(other.id()), "Workflow not found");
        assertThat(workflowsApi().getAll(0, 20).getContent())
                .extracting(row -> row.name())
                .containsExactly("From token");
    }

    private static CreateWorkflowStepRequest validateStep() {
        return new CreateWorkflowStepRequest(
                "Validate",
                StepType.VALIDATE_FIELDS,
                1,
                Map.of("requiredFields", List.of("email")));
    }

    private WorkflowController workflowsApi() {
        return new WorkflowController(workflows, new CurrentCaller());
    }

    private WorkflowExecutionController executionsApi() {
        return new WorkflowExecutionController(
                executions,
                history,
                reads,
                new CurrentCaller());
    }

    private static void authenticate(String subject) {
        authenticate(subject, ISSUER, "flowforge.admin");
    }

    private static void authenticate(String subject, String issuer, String scope) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .issuer(issuer)
                .claim("scope", scope)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private ResourceOwner ownerOf(long workflowId) {
        return jdbc.queryForObject("""
                SELECT owner_subject, owner_issuer
                FROM workflows
                WHERE id = ?
                """,
                (rs, row) -> new ResourceOwner(
                        rs.getString("owner_subject"),
                        rs.getString("owner_issuer")),
                workflowId);
    }

    private String tokenHash(long workflowId) {
        return jdbc.queryForObject(
                "SELECT webhook_token_hash FROM workflows WHERE id = ?",
                String.class,
                workflowId);
    }

    private int recoveryAttempts(long executionId) {
        return jdbc.queryForObject(
                "SELECT recovery_attempts FROM workflow_executions WHERE id = ?",
                Integer.class,
                executionId);
    }

    private String statusOf(long executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private int auditCount(long executionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_admin_audit WHERE execution_id = ?",
                Integer.class,
                executionId);
    }

    private long insertWorkflow(
            String name,
            boolean active,
            String tokenHash,
            ResourceOwner owner) {
        return jdbc.queryForObject("""
                INSERT INTO workflows (
                    name, active, webhook_token_hash, owner_subject, owner_issuer
                )
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                name,
                active,
                tokenHash,
                owner == null ? null : owner.subject(),
                owner == null ? null : owner.issuer());
    }

    @Test
    void springSessionTablesExistInTheApplicationDatabase() {
        Integer sessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spring_session",
                Integer.class);
        Integer attributes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spring_session_attributes",
                Integer.class);
        assertThat(sessions).isZero();
        assertThat(attributes).isZero();
    }

    private long insertExecution(long workflowId, String status) {
        return insertExecution(workflowId, status, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private long insertExecution(long workflowId, String status, OffsetDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_executions (
                    workflow_id, status, payload, created_at, idempotency_key
                )
                VALUES (?, ?, CAST(? AS jsonb), ?, ?)
                RETURNING id
                """,
                Long.class,
                workflowId,
                status,
                "{}",
                createdAt,
                "inserted-" + UUID.randomUUID());
    }

    private static void assertNotFound(ThrowingCall call, String reason) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException status = (ResponseStatusException) error;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(status.getReason()).isEqualTo(reason);
                });
    }

    private static void assertStatus(ThrowingCall call, HttpStatus expected, String reason) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    ResponseStatusException status = (ResponseStatusException) error;
                    assertThat(status.getStatusCode()).isEqualTo(expected);
                    assertThat(status.getReason()).isEqualTo(reason);
                });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
