package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.CreateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.ReorderWorkflowStepsRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.dto.WorkflowStepResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.exception.StepChangeException;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Claim;
import com.karimmajed.flowforge.validation.FixedAddressValidator;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
        WebhookTokenService.class,
        WebhookService.class,
        ExecutionDefinitionStore.class,
        ExecutionCheckpointStore.class,
        ExecutionStepHistoryService.class,
        FixedAddressValidator.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkflowEditorIntegrationTest {

    private static final String ISSUER = "http://auth.test/realms/flowforge";
    private static final ResourceOwner OWNER = new ResourceOwner("editor-user", ISSUER);
    private static final ResourceOwner OTHER = new ResourceOwner("other-user", ISSUER);

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
    private WebhookService webhooks;

    @Autowired
    private ExecutionCheckpointStore checkpoints;

    @Autowired
    private ExecutionStepHistoryService history;

    @BeforeEach
    void resetDatabase() {
        String url = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(url).contains(":" + postgres.getMappedPort(5432));
        assertThat(postgres.getMappedPort(5432)).isNotEqualTo(5433);

        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
    }

    @Test
    void ownerCanEditAValidationStep() {
        WorkflowResponse workflow = workflow("Checks");
        WorkflowStepResponse step = steps.create(workflow.id(), validation("Email", 1, "email"), OWNER);

        var saved = steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest(
                        "Customer email",
                        Map.of("requiredFields", List.of("email", "name")),
                        1L),
                OWNER);

        assertThat(saved.stepRevision()).isEqualTo(2L);
        assertThat(saved.step().name()).isEqualTo("Customer email");
        assertThat(saved.step().stepType()).isEqualTo(StepType.VALIDATE_FIELDS);
        assertThat(saved.step().configuration()).isEqualTo(Map.of(
                "requiredFields", List.of("email", "name")));
        assertThat(saved.step().position()).isEqualTo(1);
        assertThat(revision(workflow.id())).isEqualTo(2L);
    }

    @Test
    void ownerCanEditAnHttpsGetStepAndPostIsRejected() {
        WorkflowResponse workflow = workflow("Calls");
        WorkflowStepResponse step = steps.create(
                workflow.id(),
                http("Notify", 1, "https://example.com/orders"),
                OWNER);

        var saved = steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest(
                        "Fetch orders",
                        Map.of("method", "GET", "url", "https://example.com/orders/open"),
                        1L),
                OWNER);

        assertThat(saved.step().name()).isEqualTo("Fetch orders");
        assertThat(saved.step().configuration()).isEqualTo(Map.of(
                "method", "GET",
                "url", "https://example.com/orders/open"));

        StepChangeException invalid = (StepChangeException) assertThatThrownBy(() -> steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest(
                        "Fetch orders",
                        Map.of("method", "POST", "url", "https://example.com/orders"),
                        2L),
                OWNER)).isInstanceOf(StepChangeException.class).actual();

        assertThat(invalid.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getMessage()).isEqualTo("Method must be GET");
        assertThat(configuration(step.id())).contains("https://example.com/orders/open");
    }

    @Test
    void invalidValidationConfigurationIsRejected() {
        WorkflowResponse workflow = workflow("Checks");
        WorkflowStepResponse step = steps.create(workflow.id(), validation("Email", 1, "email"), OWNER);

        StepChangeException invalid = (StepChangeException) assertThatThrownBy(() -> steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest(
                        "Email",
                        Map.of("requiredFields", List.of()),
                        1L),
                OWNER)).isInstanceOf(StepChangeException.class).actual();

        assertThat(invalid.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getMessage()).isEqualTo("requiredFields must be a non-empty array");
        assertThat(nameOf(step.id())).isEqualTo("Email");
        assertThat(revision(workflow.id())).isEqualTo(1L);
    }

    @Test
    void deletingAStepRenumbersTheRemainingPositions() {
        WorkflowResponse workflow = workflow("Ordered");
        WorkflowStepResponse first = steps.create(workflow.id(), validation("First", 1, "a"), OWNER);
        WorkflowStepResponse second = steps.create(workflow.id(), validation("Second", 2, "b"), OWNER);
        WorkflowStepResponse third = steps.create(workflow.id(), validation("Third", 3, "c"), OWNER);

        var saved = steps.delete(workflow.id(), second.id(), 3L, OWNER);

        assertThat(saved.stepRevision()).isEqualTo(4L);
        assertThat(saved.steps()).extracting(WorkflowStepResponse::name)
                .containsExactly("First", "Third");
        assertThat(saved.steps()).extracting(WorkflowStepResponse::position)
                .containsExactly(1, 2);
        assertThat(saved.steps()).extracting(WorkflowStepResponse::id)
                .containsExactly(first.id(), third.id());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE id = ?",
                Integer.class,
                second.id())).isZero();
    }

    @Test
    void reorderSeveralStepsIsAtomicAndKeepsUniquePositions() {
        WorkflowResponse workflow = workflow("Ordered");
        WorkflowStepResponse first = steps.create(workflow.id(), validation("First", 1, "a"), OWNER);
        WorkflowStepResponse second = steps.create(workflow.id(), validation("Second", 2, "b"), OWNER);
        WorkflowStepResponse third = steps.create(workflow.id(), validation("Third", 3, "c"), OWNER);

        var saved = steps.reorder(
                workflow.id(),
                new ReorderWorkflowStepsRequest(List.of(third.id(), first.id(), second.id()), 3L),
                OWNER);

        assertThat(saved.steps()).extracting(WorkflowStepResponse::name)
                .containsExactly("Third", "First", "Second");
        assertThat(saved.steps()).extracting(WorkflowStepResponse::position)
                .containsExactly(1, 2, 3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT position FROM workflow_steps WHERE workflow_id = ?
                    GROUP BY position HAVING COUNT(*) > 1
                ) duplicated
                """,
                Integer.class,
                workflow.id())).isZero();

        assertThatThrownBy(() -> steps.reorder(
                workflow.id(),
                new ReorderWorkflowStepsRequest(List.of(first.id(), second.id()), saved.stepRevision()),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("The step list does not match this workflow.");
        assertThat(names(workflow.id())).containsExactly("Third", "First", "Second");
    }

    @Test
    void aStaleRevisionDoesNotOverwrite() {
        WorkflowResponse workflow = workflow("Checks");
        WorkflowStepResponse step = steps.create(workflow.id(), validation("Email", 1, "email"), OWNER);
        steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest("Newer", Map.of("requiredFields", List.of("email")), 1L),
                OWNER);

        StepChangeException conflict = (StepChangeException) assertThatThrownBy(() -> steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest("Older", Map.of("requiredFields", List.of("name")), 1L),
                OWNER)).isInstanceOf(StepChangeException.class).actual();

        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getTitle()).isEqualTo("Step definition conflict");
        assertThat(conflict.getMessage()).isEqualTo("This workflow changed. Refresh and try again.");
        assertThat(nameOf(step.id())).isEqualTo("Newer");
        assertThat(configuration(step.id())).contains("email");
    }

    @Test
    void anotherOwnerCannotEditDeleteOrReorder() {
        WorkflowResponse workflow = workflow("Private");
        WorkflowStepResponse step = steps.create(workflow.id(), validation("Email", 1, "email"), OWNER);

        assertThatThrownBy(() -> steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest("Stolen", Map.of("requiredFields", List.of("email")), 1L),
                OTHER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> steps.delete(workflow.id(), step.id(), 1L, OTHER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> steps.reorder(
                workflow.id(),
                new ReorderWorkflowStepsRequest(List.of(step.id()), 1L),
                OTHER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(nameOf(step.id())).isEqualTo("Email");
    }

    @Test
    void startedRunsKeepTheirDefinitionAfterEdits() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Runnable", null), OWNER);
        workflows.updateStatus(workflow.id(), new UpdateWorkflowStatusRequest(true), OWNER);
        WorkflowStepResponse step = steps.create(workflow.id(), validation("Email", 1, "email"), OWNER);
        byte[] body = "{\"email\":\"ada@example.com\"}".getBytes(StandardCharsets.UTF_8);
        var started = executions.run(
                workflow.id(),
                "manual-editor",
                body,
                Map.of("email", "ada@example.com"),
                OWNER);

        assertThat(started.replayed()).isFalse();
        assertThat(started.response().status()).isEqualTo(ExecutionStatus.RECEIVED);
        assertThat(frozen(started.response().id())).isTrue();
        assertThat(outboxWaiting(started.response().id())).isEqualTo(1);

        steps.update(
                workflow.id(),
                step.id(),
                new UpdateWorkflowStepRequest(
                        "Renamed",
                        Map.of("requiredFields", List.of("name")),
                        revision(workflow.id())),
                OWNER);

        Claim claim = checkpoints.claimNext(started.response().id());
        assertThat(claim.kind()).isEqualTo(Claim.Kind.STEP);
        assertThat(claim.work().stepName()).isEqualTo("Email");
        assertThat(claim.work().configuration()).isEqualTo(Map.of("requiredFields", List.of("email")));
        assertThat(history.getSteps(started.response().id(), OWNER))
                .extracting(item -> item.name())
                .containsExactly("Email");
        assertThat(snapshotName(started.response().id())).isEqualTo("Email");

        var later = executions.run(
                workflow.id(),
                "manual-later",
                body,
                Map.of("email", "ada@example.com"),
                OWNER);
        assertThat(snapshotName(later.response().id())).isEqualTo("Renamed");
        assertThat(later.response().status()).isEqualTo(ExecutionStatus.RECEIVED);
    }

    @Test
    void webhookRunsStayReceivedAndKeepTheirOriginalSteps() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Hooked", null), OWNER);
        workflows.updateStatus(workflow.id(), new UpdateWorkflowStatusRequest(true), OWNER);
        WorkflowStepResponse step = steps.create(workflow.id(), validation("Order", 1, "orderId"), OWNER);
        String token = workflows.rotateWebhookToken(workflow.id(), OWNER).token();

        var triggered = webhooks.trigger(token, "hook-1", Map.of("orderId", "o-1"));

        assertThat(triggered.replayed()).isFalse();
        assertThat(triggered.response().status()).isEqualTo(ExecutionStatus.RECEIVED);
        assertThat(frozen(triggered.response().id())).isTrue();
        assertThat(outboxWaiting(triggered.response().id())).isEqualTo(1);

        steps.delete(workflow.id(), step.id(), revision(workflow.id()), OWNER);

        assertThat(snapshotName(triggered.response().id())).isEqualTo("Order");
        assertThat(history.getSteps(triggered.response().id(), OWNER))
                .extracting(item -> item.name())
                .containsExactly("Order");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                triggered.response().id())).isEqualTo("RECEIVED");
        var replay = webhooks.trigger(token, "hook-1", Map.of("orderId", "o-1"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().id()).isEqualTo(triggered.response().id());
    }

    private WorkflowResponse workflow(String name) {
        return workflows.create(new CreateWorkflowRequest(name, null), OWNER);
    }

    private static CreateWorkflowStepRequest validation(String name, int position, String field) {
        return new CreateWorkflowStepRequest(
                name,
                StepType.VALIDATE_FIELDS,
                position,
                Map.of("requiredFields", List.of(field)));
    }

    private static CreateWorkflowStepRequest http(String name, int position, String url) {
        return new CreateWorkflowStepRequest(
                name,
                StepType.HTTP_REQUEST,
                position,
                Map.of("method", "GET", "url", url));
    }

    private long revision(long workflowId) {
        return jdbc.queryForObject(
                "SELECT step_revision FROM workflows WHERE id = ?",
                Long.class,
                workflowId);
    }

    private String nameOf(long stepId) {
        return jdbc.queryForObject(
                "SELECT name FROM workflow_steps WHERE id = ?",
                String.class,
                stepId);
    }

    private List<String> names(long workflowId) {
        return jdbc.query(
                """
                SELECT name FROM workflow_steps
                WHERE workflow_id = ?
                ORDER BY position
                """,
                (rs, rowNum) -> rs.getString("name"),
                workflowId);
    }

    private String configuration(long stepId) {
        return jdbc.queryForObject(
                "SELECT configuration::text FROM workflow_steps WHERE id = ?",
                String.class,
                stepId);
    }

    private boolean frozen(long executionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT definition_frozen FROM workflow_executions WHERE id = ?",
                Boolean.class,
                executionId));
    }

    private int outboxWaiting(long executionId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM execution_outbox
                WHERE execution_id = ?
                  AND published_at IS NULL
                """,
                Integer.class,
                executionId);
    }

    private String snapshotName(long executionId) {
        return jdbc.queryForObject(
                """
                SELECT name
                FROM execution_step_definitions
                WHERE execution_id = ?
                ORDER BY position
                LIMIT 1
                """,
                String.class,
                executionId);
    }
}
