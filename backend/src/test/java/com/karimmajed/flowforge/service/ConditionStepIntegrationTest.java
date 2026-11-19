package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.CreateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.dto.ReorderWorkflowStepsRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.dto.WorkflowStepResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.exception.StepChangeException;
import com.karimmajed.flowforge.http.HttpsGetExecutor;
import com.karimmajed.flowforge.security.ResourceOwner;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
class ConditionStepIntegrationTest {

    private static final String ISSUER = "http://auth.test/realms/flowforge";
    private static final ResourceOwner OWNER = new ResourceOwner("condition-user", ISSUER);

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
    private ExecutionCheckpointStore checkpoints;

    @Autowired
    private ExecutionStepHistoryService history;

    private WorkflowExecutionProcessor processor;

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
        processor = new WorkflowExecutionProcessor(checkpoints, mock(HttpsGetExecutor.class));
    }

    @Test
    void trueBranchRunsAndFalseBranchIsSkipped() {
        WorkflowResponse workflow = activeWorkflow();
        Branch branch = branch(workflow, "EQUALS", 10);
        long executionId = run(
                workflow,
                "true-branch",
                Map.of("amount", 10, "email", "ada@example.com", "ready", true));

        processor.process(executionId);

        assertThat(status(executionId)).isEqualTo("SUCCEEDED");
        List<ExecutionStepResponse> recorded = history.getSteps(executionId, OWNER);
        assertThat(recorded).extracting(ExecutionStepResponse::name)
                .containsExactly("Amount is 10", "Notify", "Record the small amount", "Finish");
        assertThat(recorded).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SKIPPED,
                        ExecutionStepStatus.SUCCEEDED);
        assertThat(recorded.get(0).branchTaken()).isTrue();
        assertThat(recorded.get(2).branchTaken()).isNull();
        assertThat(checkpointCount(executionId)).isEqualTo(4);

        processor.process(executionId);

        assertThat(status(executionId)).isEqualTo("SUCCEEDED");
        assertThat(checkpointCount(executionId)).isEqualTo(4);
        assertThat(history.getSteps(executionId, OWNER))
                .extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SKIPPED,
                        ExecutionStepStatus.SUCCEEDED);
        assertThat(branch.condition().id()).isNotNull();
    }

    @Test
    void falseBranchRunsAndTrueBranchIsSkipped() {
        WorkflowResponse workflow = activeWorkflow();
        branch(workflow, "LESS_THAN", 10);
        long executionId = run(
                workflow,
                "false-branch",
                Map.of("amount", 11, "other", "kept", "ready", true));

        processor.process(executionId);

        assertThat(status(executionId)).isEqualTo("SUCCEEDED");
        List<ExecutionStepResponse> recorded = history.getSteps(executionId, OWNER);
        assertThat(recorded).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SKIPPED,
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SUCCEEDED);
        assertThat(recorded.get(0).branchTaken()).isFalse();
    }

    @Test
    void aFailureInsideTheTakenBranchDoesNotSkipTheRemainingTakenSteps() {
        WorkflowResponse workflow = activeWorkflow();
        branch(workflow, "GREATER_THAN", 10);
        long executionId = run(workflow, "branch-failure", Map.of("amount", 11, "ready", true));

        processor.process(executionId);

        assertThat(status(executionId)).isEqualTo("FAILED");
        List<ExecutionStepResponse> recorded = history.getSteps(executionId, OWNER);
        assertThat(recorded).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.FAILED,
                        ExecutionStepStatus.SKIPPED,
                        ExecutionStepStatus.NOT_STARTED);
        assertThat(recorded.get(1).failureReason()).contains("Missing required field");
        assertThat(recorded.get(0).branchTaken()).isTrue();
    }

    @Test
    void incompatiblePayloadFailsTheConditionAndLeavesLaterStepsUnstarted() {
        WorkflowResponse workflow = activeWorkflow();
        branch(workflow, "EQUALS", 10);
        long executionId = run(
                workflow,
                "wrong-type",
                Map.of("amount", "10", "email", "ada@example.com", "ready", true));

        processor.process(executionId);

        assertThat(status(executionId)).isEqualTo("FAILED");
        List<ExecutionStepResponse> recorded = history.getSteps(executionId, OWNER);
        assertThat(recorded.get(0).status()).isEqualTo(ExecutionStepStatus.FAILED);
        assertThat(recorded.get(0).failureReason())
                .isEqualTo("Condition 'amount': expected NUMBER, but received STRING.");
        assertThat(recorded).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.FAILED,
                        ExecutionStepStatus.NOT_STARTED,
                        ExecutionStepStatus.NOT_STARTED,
                        ExecutionStepStatus.NOT_STARTED);
    }

    @Test
    void aNonNumericGreaterThanFailsWithoutSkippingEitherBranch() {
        WorkflowResponse workflow = activeWorkflow();
        branch(workflow, "GREATER_THAN", 10);
        long executionId = run(
                workflow,
                "greater-string",
                Map.of("amount", "11", "email", "ada@example.com", "ready", true));

        processor.process(executionId);

        String reason = "Condition 'amount': operator GREATER_THAN requires numbers (expected NUMBER, received STRING).";
        assertThat(status(executionId)).isEqualTo("FAILED");
        List<ExecutionStepResponse> recorded = history.getSteps(executionId, OWNER);
        assertThat(recorded.get(0).failureReason()).isEqualTo(reason);
        assertThat(recorded.get(0).branchTaken()).isNull();
        assertThat(recorded).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.FAILED,
                        ExecutionStepStatus.NOT_STARTED,
                        ExecutionStepStatus.NOT_STARTED,
                        ExecutionStepStatus.NOT_STARTED);
        assertThat(jdbc.queryForObject(
                "SELECT failure_reason FROM workflow_executions WHERE id = ?",
                String.class,
                executionId)).isEqualTo(reason);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND status = 'SKIPPED'
                """,
                Integer.class,
                executionId)).isZero();
    }

    @Test
    void aRunningConditionIsNotDecidedAgain() {
        WorkflowResponse workflow = activeWorkflow();
        branch(workflow, "EQUALS", 10);
        long executionId = run(
                workflow,
                "busy-condition",
                Map.of("amount", 10, "email", "ada@example.com", "ready", true));

        checkpoints.claimNext(executionId);

        processor.process(executionId);

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND status = 'SKIPPED'
                """,
                Integer.class,
                executionId)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT branch_taken
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND status = 'RUNNING'
                """,
                Boolean.class,
                executionId)).isNull();
        assertThat(status(executionId)).isEqualTo("RUNNING");
    }

    @Test
    void aLaterEditDoesNotChangeTheFrozenDecision() {
        WorkflowResponse workflow = activeWorkflow();
        Branch branch = branch(workflow, "EQUALS", 10);
        long executionId = run(
                workflow,
                "frozen-first",
                Map.of("amount", 10, "email", "ada@example.com", "ready", true));
        processor.process(executionId);

        steps.update(
                workflow.id(),
                branch.condition().id(),
                new UpdateWorkflowStepRequest(
                        "Amount changed",
                        conditionConfig("EQUALS", 99, branch.notified().id(), branch.small().id()),
                        revision(workflow.id())),
                OWNER);

        assertThat(snapshotValue(executionId)).isEqualTo("10");
        assertThat(history.getSteps(executionId, OWNER)).extracting(ExecutionStepResponse::name)
                .containsExactly("Amount is 10", "Notify", "Record the small amount", "Finish");

        long later = run(
                workflow,
                "frozen-later",
                Map.of("amount", 10, "email", "ada@example.com", "other", "kept", "ready", true));
        processor.process(later);
        assertThat(history.getSteps(later, OWNER).get(0).name()).isEqualTo("Amount changed");
        assertThat(history.getSteps(later, OWNER).get(0).branchTaken()).isFalse();
        assertThat(history.getSteps(later, OWNER)).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SKIPPED,
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SUCCEEDED);

        steps.delete(workflow.id(), branch.condition().id(), revision(workflow.id()), OWNER);
        assertThat(history.getSteps(executionId, OWNER).get(2).status())
                .isEqualTo(ExecutionStepStatus.SKIPPED);
        assertThat(snapshotValue(executionId)).isEqualTo("10");
    }

    @Test
    void equalityAndNumericBoundariesUseTheStoredJsonValues() {
        WorkflowResponse workflow = activeWorkflow();
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "email"), OWNER);
        WorkflowStepResponse small = steps.create(workflow.id(), validation("Small", 3, "other"), OWNER);
        steps.create(workflow.id(), validation("Finish", 4, "ready"), OWNER);
        steps.create(workflow.id(), condition("Amount is one", 1, "EQUALS", 1, notify.id(), small.id()), OWNER);
        Map<String, Object> equal = new HashMap<>();
        equal.put("amount", 1.0d);
        equal.put("email", "ada@example.com");
        equal.put("ready", true);
        long equalId = run(workflow, "equal-decimal", equal);
        processor.process(equalId);
        assertThat(history.getSteps(equalId, OWNER).get(0).branchTaken()).isTrue();

        WorkflowResponse bounds = activeWorkflow();
        branch(bounds, "GREATER_THAN", 10);
        long same = run(bounds, "not-greater", Map.of("amount", 10, "other", "kept", "ready", true));
        processor.process(same);
        assertThat(history.getSteps(same, OWNER).get(0).branchTaken()).isFalse();
        long above = run(bounds, "greater", Map.of("amount", 11, "email", "ada@example.com", "ready", true));
        processor.process(above);
        assertThat(history.getSteps(above, OWNER).get(0).branchTaken()).isTrue();
    }

    @Test
    void aMissingFieldFailsTheCondition() {
        WorkflowResponse workflow = activeWorkflow();
        branch(workflow, "EQUALS", 10);
        long executionId = run(workflow, "missing-amount", Map.of("email", "ada@example.com", "ready", true));

        processor.process(executionId);

        assertThat(history.getSteps(executionId, OWNER).get(0).failureReason())
                .isEqualTo("Condition field is missing: amount");
        assertThat(history.getSteps(executionId, OWNER)).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.FAILED,
                        ExecutionStepStatus.NOT_STARTED,
                        ExecutionStepStatus.NOT_STARTED,
                        ExecutionStepStatus.NOT_STARTED);
    }

    @Test
    void existingSequentialStepsStillRunInOrder() {
        WorkflowResponse workflow = activeWorkflow();
        steps.create(workflow.id(), validation("First", 1, "email"), OWNER);
        steps.create(workflow.id(), validation("Second", 2, "ready"), OWNER);
        long executionId = run(workflow, "sequential", Map.of("email", "ada@example.com", "ready", true));

        processor.process(executionId);

        assertThat(status(executionId)).isEqualTo("SUCCEEDED");
        assertThat(history.getSteps(executionId, OWNER))
                .extracting(ExecutionStepResponse::status)
                .containsExactly(ExecutionStepStatus.SUCCEEDED, ExecutionStepStatus.SUCCEEDED);
    }

    @Test
    void invalidBranchesAreRejectedAndALegalReorderStaysInsideTheBlock() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Branches", null), OWNER);
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "email"), OWNER);
        WorkflowStepResponse secondTrue = steps.create(workflow.id(), validation("Also notify", 3, "email"), OWNER);
        WorkflowStepResponse small = steps.create(workflow.id(), validation("Small", 4, "other"), OWNER);
        WorkflowStepResponse finish = steps.create(workflow.id(), validation("Finish", 5, "ready"), OWNER);
        WorkflowStepResponse outside = steps.create(workflow.id(), validation("Outside", 6, "ready"), OWNER);

        assertThatThrownBy(() -> steps.create(
                workflow.id(),
                condition("Amount", 1, "EQUALS", 10, 999L, small.id()),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("A branch step is unknown");

        WorkflowStepResponse condition = steps.create(
                workflow.id(),
                condition(
                        "Amount",
                        1,
                        "EQUALS",
                        10,
                        List.of(notify.id(), secondTrue.id()),
                        List.of(small.id())),
                OWNER);

        assertThatThrownBy(() -> steps.update(
                workflow.id(),
                condition.id(),
                new UpdateWorkflowStepRequest(
                        "Amount",
                        conditionConfig("EQUALS", 10, List.of(finish.id()), List.of(small.id())),
                        revision(workflow.id())),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("Branch steps must be the contiguous steps after the condition");
        assertThatThrownBy(() -> steps.delete(
                workflow.id(),
                notify.id(),
                revision(workflow.id()),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("A branch step is unknown");
        assertThat(names(workflow.id()))
                .containsExactly("Amount", "Notify", "Also notify", "Small", "Finish", "Outside");

        var reordered = steps.reorder(
                workflow.id(),
                new ReorderWorkflowStepsRequest(
                        List.of(
                                condition.id(),
                                secondTrue.id(),
                                notify.id(),
                                small.id(),
                                finish.id(),
                                outside.id()),
                        revision(workflow.id())),
                OWNER);
        assertThat(reordered.steps()).extracting(WorkflowStepResponse::name)
                .containsExactly("Amount", "Also notify", "Notify", "Small", "Finish", "Outside");
        assertThat(numbers(reordered.steps().get(0).configuration().get("trueSteps")))
                .containsExactly(secondTrue.id(), notify.id());

        assertThatThrownBy(() -> steps.reorder(
                workflow.id(),
                new ReorderWorkflowStepsRequest(
                        List.of(
                                condition.id(),
                                small.id(),
                                secondTrue.id(),
                                notify.id(),
                                finish.id(),
                                outside.id()),
                        reordered.stepRevision()),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("Branch steps must be the contiguous steps after the condition");
        assertThat(names(workflow.id()))
                .containsExactly("Amount", "Also notify", "Notify", "Small", "Finish", "Outside");
    }

    @Test
    void nestedAndSharedBranchStepsAreRejected() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Nested", null), OWNER);
        WorkflowStepResponse first = steps.create(workflow.id(), validation("First", 3, "email"), OWNER);
        WorkflowStepResponse second = steps.create(workflow.id(), validation("Second", 4, "other"), OWNER);
        WorkflowStepResponse nested = steps.create(
                workflow.id(),
                condition("Inner", 2, "EXISTS", null, first.id(), second.id()),
                OWNER);

        assertThatThrownBy(() -> steps.create(
                workflow.id(),
                condition("Outer", 1, "EQUALS", 1, List.of(nested.id()), List.of()),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("A branch cannot contain a condition");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ? AND name = 'Outer'",
                Integer.class,
                workflow.id())).isZero();

        WorkflowResponse other = workflows.create(new CreateWorkflowRequest("Shared", null), OWNER);
        WorkflowStepResponse left = steps.create(other.id(), validation("Left", 2, "email"), OWNER);
        WorkflowStepResponse right = steps.create(other.id(), validation("Right", 3, "other"), OWNER);
        steps.create(other.id(), condition("First condition", 1, "EQUALS", 1, left.id(), right.id()), OWNER);
        WorkflowStepResponse tail = steps.create(other.id(), validation("Tail", 4, "ready"), OWNER);
        assertThatThrownBy(() -> steps.create(
                other.id(),
                condition("Second condition", 5, "EQUALS", 1, left.id(), tail.id()),
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("A step is in more than one branch");
    }

    @Test
    void existsWithANullValueTakesTheTrueBranch() {
        WorkflowResponse workflow = activeWorkflow();
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "email"), OWNER);
        WorkflowStepResponse small = steps.create(workflow.id(), validation("Small", 3, "other"), OWNER);
        steps.create(workflow.id(), validation("Finish", 4, "ready"), OWNER);
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("field", "amount");
        configuration.put("operator", "EXISTS");
        configuration.put("trueSteps", List.of(notify.id()));
        configuration.put("falseSteps", List.of(small.id()));
        steps.create(workflow.id(), new CreateWorkflowStepRequest(
                "Amount is present",
                StepType.CONDITION,
                1,
                configuration), OWNER);
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", null);
        payload.put("email", "ada@example.com");
        payload.put("ready", true);
        long executionId = run(workflow, "exists-null", payload);

        processor.process(executionId);

        assertThat(history.getSteps(executionId, OWNER)).extracting(ExecutionStepResponse::status)
                .containsExactly(
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SUCCEEDED,
                        ExecutionStepStatus.SKIPPED,
                        ExecutionStepStatus.SUCCEEDED);
        assertThat(history.getSteps(executionId, OWNER).get(0).branchTaken()).isTrue();
    }

    @Test
    void insertingAConditionAtTheBeginningShiftsEveryLaterStep() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Beginning", null), OWNER);
        WorkflowStepResponse first = steps.create(workflow.id(), validation("First", 1, "email"), OWNER);
        WorkflowStepResponse second = steps.create(workflow.id(), validation("Second", 2, "ready"), OWNER);

        WorkflowStepResponse condition = steps.create(
                workflow.id(),
                condition("Amount is 10", 1, "EQUALS", 10, first.id(), second.id()),
                revision(workflow.id()),
                OWNER);

        assertInserted(
                workflow.id(),
                List.of(condition.id(), first.id(), second.id()),
                List.of("Amount is 10", "First", "Second"),
                first.id(),
                second.id());
        assertThat(fieldNames(first.id())).containsExactly("email");
        assertThat(fieldNames(second.id())).containsExactly("ready");
    }

    @Test
    void insertingAConditionInTheMiddleShiftsTheBranchAndTheTail() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Middle", null), OWNER);
        WorkflowStepResponse prefix = steps.create(workflow.id(), validation("Prefix", 1, "email"), OWNER);
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "ready"), OWNER);
        WorkflowStepResponse record = steps.create(workflow.id(), validation("Record", 3, "other"), OWNER);
        WorkflowStepResponse tail = steps.create(workflow.id(), validation("Tail", 4, "done"), OWNER);

        WorkflowStepResponse condition = steps.create(
                workflow.id(),
                condition("Amount is 10", 2, "EQUALS", 10, notify.id(), record.id()),
                revision(workflow.id()),
                OWNER);

        assertInserted(
                workflow.id(),
                List.of(prefix.id(), condition.id(), notify.id(), record.id(), tail.id()),
                List.of("Prefix", "Amount is 10", "Notify", "Record", "Tail"),
                notify.id(),
                record.id());
        assertThat(fieldNames(prefix.id())).containsExactly("email");
        assertThat(fieldNames(tail.id())).containsExactly("done");
    }

    @Test
    void insertingAConditionAtTheEndShiftsOnlyTheBranchSteps() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("End", null), OWNER);
        WorkflowStepResponse prefix = steps.create(workflow.id(), validation("Prefix", 1, "email"), OWNER);
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "ready"), OWNER);
        WorkflowStepResponse record = steps.create(workflow.id(), validation("Record", 3, "other"), OWNER);

        WorkflowStepResponse condition = steps.create(
                workflow.id(),
                condition("Amount is 10", 2, "EQUALS", 10, notify.id(), record.id()),
                revision(workflow.id()),
                OWNER);

        assertInserted(
                workflow.id(),
                List.of(prefix.id(), condition.id(), notify.id(), record.id()),
                List.of("Prefix", "Amount is 10", "Notify", "Record"),
                notify.id(),
                record.id());
    }

    @Test
    void aStaleConditionInsertDoesNotShift() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Stale", null), OWNER);
        WorkflowStepResponse first = steps.create(workflow.id(), validation("First", 1, "email"), OWNER);
        WorkflowStepResponse second = steps.create(workflow.id(), validation("Second", 2, "ready"), OWNER);
        long seen = revision(workflow.id());
        steps.update(
                workflow.id(),
                first.id(),
                new UpdateWorkflowStepRequest("First", Map.of("requiredFields", List.of("email")), seen),
                OWNER);

        assertThatThrownBy(() -> steps.create(
                workflow.id(),
                condition("Amount is 10", 1, "EQUALS", 10, first.id(), second.id()),
                seen,
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("This workflow changed. Refresh and try again.");

        assertThat(steps.getAll(workflow.id(), OWNER)).extracting(WorkflowStepResponse::name)
                .containsExactly("First", "Second");
        assertThat(steps.getAll(workflow.id(), OWNER)).extracting(WorkflowStepResponse::position)
                .containsExactly(1, 2);
        assertThat(steps.getAll(workflow.id(), OWNER)).extracting(WorkflowStepResponse::id)
                .containsExactly(first.id(), second.id());
        assertThat(revision(workflow.id())).isEqualTo(seen + 1);
    }

    @Test
    void anInvalidConditionInsertRollsTheShiftBack() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Rollback", null), OWNER);
        WorkflowStepResponse first = steps.create(workflow.id(), validation("First", 1, "email"), OWNER);
        WorkflowStepResponse second = steps.create(workflow.id(), validation("Second", 2, "ready"), OWNER);
        WorkflowStepResponse third = steps.create(workflow.id(), validation("Third", 3, "other"), OWNER);
        long seen = revision(workflow.id());

        assertThatThrownBy(() -> steps.create(
                workflow.id(),
                condition("Amount is 10", 2, "EQUALS", 10, first.id(), second.id()),
                seen,
                OWNER))
                .isInstanceOf(StepChangeException.class)
                .hasMessage("Branch steps must be the contiguous steps after the condition");

        assertThat(steps.getAll(workflow.id(), OWNER)).extracting(WorkflowStepResponse::id)
                .containsExactly(first.id(), second.id(), third.id());
        assertThat(steps.getAll(workflow.id(), OWNER)).extracting(WorkflowStepResponse::position)
                .containsExactly(1, 2, 3);
        assertThat(fieldNames(first.id())).containsExactly("email");
        assertThat(revision(workflow.id())).isEqualTo(seen);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_steps WHERE workflow_id = ? AND step_type = 'CONDITION'",
                Integer.class,
                workflow.id())).isZero();
    }

    @Test
    void shiftingKeepsExistingBranchReferencesAndLeavesSnapshotsAlone() {
        WorkflowResponse workflow = activeWorkflow();
        WorkflowStepResponse prefix = steps.create(workflow.id(), validation("Prefix", 1, "email"), OWNER);
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "ready"), OWNER);
        WorkflowStepResponse record = steps.create(workflow.id(), validation("Record", 3, "other"), OWNER);
        WorkflowStepResponse tail = steps.create(workflow.id(), validation("Tail", 4, "done"), OWNER);
        WorkflowStepResponse existing = steps.create(
                workflow.id(),
                condition("Later amount", 3, "EQUALS", 10, record.id(), tail.id()),
                revision(workflow.id()),
                OWNER);
        long executionId = run(workflow, "shift-snapshot", Map.of("amount", 10, "email", "ada@example.com"));
        List<String> frozen = snapshotRows(executionId);

        WorkflowStepResponse inserted = steps.create(
                workflow.id(),
                condition("Earlier amount", 1, "EQUALS", 10, prefix.id(), notify.id()),
                revision(workflow.id()),
                OWNER);

        List<WorkflowStepResponse> saved = steps.getAll(workflow.id(), OWNER);
        assertThat(saved).extracting(WorkflowStepResponse::id).containsExactly(
                inserted.id(),
                prefix.id(),
                notify.id(),
                existing.id(),
                record.id(),
                tail.id());
        assertThat(saved).extracting(WorkflowStepResponse::position).containsExactly(1, 2, 3, 4, 5, 6);
        WorkflowStepResponse kept = saved.stream()
                .filter(step -> step.id().equals(existing.id()))
                .findFirst()
                .orElseThrow();
        assertThat(numbers(kept.configuration().get("trueSteps"))).containsExactly(record.id());
        assertThat(numbers(kept.configuration().get("falseSteps"))).containsExactly(tail.id());
        assertThat(snapshotRows(executionId)).isEqualTo(frozen);
        assertThat(frozen).noneMatch(row -> row.contains("Earlier amount"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_step_definitions WHERE execution_id = ?",
                Integer.class,
                executionId)).isEqualTo(5);
    }

    private void assertInserted(
            long workflowId,
            List<Long> ids,
            List<String> names,
            long trueStep,
            long falseStep) {
        List<WorkflowStepResponse> saved = steps.getAll(workflowId, OWNER);
        assertThat(saved).extracting(WorkflowStepResponse::id).containsExactlyElementsOf(ids);
        assertThat(saved).extracting(WorkflowStepResponse::name).containsExactlyElementsOf(names);
        assertThat(saved).extracting(WorkflowStepResponse::position)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, ids.size()).boxed().toList());
        WorkflowStepResponse condition = saved.stream()
                .filter(step -> step.stepType() == StepType.CONDITION)
                .findFirst()
                .orElseThrow();
        assertThat(numbers(condition.configuration().get("trueSteps"))).containsExactly(trueStep);
        assertThat(numbers(condition.configuration().get("falseSteps"))).containsExactly(falseStep);
    }

    private List<String> fieldNames(long stepId) {
        return jdbc.queryForList(
                """
                SELECT field
                FROM workflow_steps, jsonb_array_elements_text(configuration -> 'requiredFields') AS field
                WHERE id = ?
                ORDER BY field
                """,
                String.class,
                stepId);
    }

    private List<String> snapshotRows(long executionId) {
        return jdbc.query(
                """
                SELECT workflow_step_id || ',' || position || ',' || step_type || ',' || name
                       || ',' || configuration::text
                FROM execution_step_definitions
                WHERE execution_id = ?
                ORDER BY position, workflow_step_id
                """,
                (rs, rowNum) -> rs.getString(1),
                executionId);
    }

    private WorkflowResponse activeWorkflow() {
        WorkflowResponse workflow = workflows.create(new CreateWorkflowRequest("Conditional", null), OWNER);
        workflows.updateStatus(workflow.id(), new UpdateWorkflowStatusRequest(true), OWNER);
        return workflow;
    }

    private Branch branch(WorkflowResponse workflow, String operator, Object value) {
        WorkflowStepResponse notify = steps.create(workflow.id(), validation("Notify", 2, "email"), OWNER);
        WorkflowStepResponse small = steps.create(workflow.id(), validation("Record the small amount", 3, "other"), OWNER);
        steps.create(workflow.id(), validation("Finish", 4, "ready"), OWNER);
        WorkflowStepResponse condition = steps.create(
                workflow.id(),
                condition("Amount is 10", 1, operator, value, notify.id(), small.id()),
                OWNER);
        return new Branch(condition, notify, small);
    }

    private long run(WorkflowResponse workflow, String key, Map<String, Object> payload) {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        var started = executions.run(workflow.id(), key, body, payload, OWNER);
        assertThat(started.replayed()).isFalse();
        assertThat(started.response().status()).isEqualTo(ExecutionStatus.RECEIVED);
        return started.response().id();
    }

    private static CreateWorkflowStepRequest validation(String name, int position, String field) {
        return new CreateWorkflowStepRequest(
                name,
                StepType.VALIDATE_FIELDS,
                position,
                Map.of("requiredFields", List.of(field)));
    }

    private static CreateWorkflowStepRequest condition(
            String name,
            int position,
            String operator,
            Object value,
            long trueStep,
            long falseStep) {
        return condition(name, position, operator, value, List.of(trueStep), List.of(falseStep));
    }

    private static CreateWorkflowStepRequest condition(
            String name,
            int position,
            String operator,
            Object value,
            List<Long> trueSteps,
            List<Long> falseSteps) {
        return new CreateWorkflowStepRequest(
                name,
                StepType.CONDITION,
                position,
                conditionConfig(operator, value, trueSteps, falseSteps));
    }

    private static Map<String, Object> conditionConfig(
            String operator,
            Object value,
            long trueStep,
            long falseStep) {
        return conditionConfig(operator, value, List.of(trueStep), List.of(falseStep));
    }

    private static Map<String, Object> conditionConfig(
            String operator,
            Object value,
            List<Long> trueSteps,
            List<Long> falseSteps) {
        Map<String, Object> configuration = new HashMap<>();
        configuration.put("field", "amount");
        configuration.put("operator", operator);
        if (value != null || "EQUALS".equals(operator) || "NOT_EQUALS".equals(operator)) {
            configuration.put("value", value);
        }
        configuration.put("trueSteps", trueSteps);
        configuration.put("falseSteps", falseSteps);
        return configuration;
    }

    private long revision(long workflowId) {
        return jdbc.queryForObject(
                "SELECT step_revision FROM workflows WHERE id = ?",
                Long.class,
                workflowId);
    }

    private String status(long executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private int checkpointCount(long executionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_step_checkpoints WHERE execution_id = ?",
                Integer.class,
                executionId);
    }

    private String snapshotValue(long executionId) {
        return jdbc.queryForObject(
                """
                SELECT configuration ->> 'value'
                FROM execution_step_definitions
                WHERE execution_id = ?
                  AND step_type = 'CONDITION'
                """,
                String.class,
                executionId);
    }

    private static List<Long> numbers(Object value) {
        return ((List<?>) value).stream()
                .map(item -> ((Number) item).longValue())
                .toList();
    }

    private List<String> names(long workflowId) {
        return jdbc.query(
                """
                SELECT name
                FROM workflow_steps
                WHERE workflow_id = ?
                ORDER BY position
                """,
                (rs, rowNum) -> rs.getString("name"),
                workflowId);
    }

    private record Branch(
            WorkflowStepResponse condition,
            WorkflowStepResponse notified,
            WorkflowStepResponse small
    ) {}
}
