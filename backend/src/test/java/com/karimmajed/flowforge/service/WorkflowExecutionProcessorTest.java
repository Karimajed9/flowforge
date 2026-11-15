package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.entity.WorkflowStep;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.exception.NonRetryableExecutionException;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowStepRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionProcessorTest {

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowStepRepository stepRepository;

    @InjectMocks
    private WorkflowExecutionProcessor processor;

    @Test
    void validPayloadSucceeds() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of(
                "orderId", 123,
                "amount", 150,
                "customerId", 45
            )
        );

        WorkflowStep step = createValidationStep(workflow);

        mockProcessing(execution, List.of(step));

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.SUCCEEDED);

        assertThat(execution.getFailureReason())
            .isNull();
    }

    @Test
    void missingRequiredFieldFails() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of(
                "orderId", 123,
                "amount", 150
            )
        );

        WorkflowStep step = createValidationStep(workflow);

        mockProcessing(execution, List.of(step));

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.FAILED);

        assertThat(execution.getFailureReason())
            .isEqualTo(
                "Missing required field: customerId"
            );
    }

    @Test
    void duplicateDeliveryDoesNotRunStepsAgain() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        execution.markRunning();
        execution.markSucceeded();

        when(executionRepository.findForProcessing(21L))
            .thenReturn(Optional.of(execution));

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.SUCCEEDED);

        verifyNoInteractions(stepRepository);
    }

    @Test
    void emptyWorkflowFails() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        mockProcessing(execution, List.of());

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.FAILED);

        assertThat(execution.getFailureReason())
            .isEqualTo("Workflow contains no steps");
    }

    @Test
    void unsupportedHttpStepFails() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        WorkflowStep step = new WorkflowStep(
            workflow,
            "Send HTTP Request",
            StepType.HTTP_REQUEST,
            1
        );

        step.setConfiguration(Map.of(
            "method", "GET",
            "url", "https://example.com"
        ));

        mockProcessing(execution, List.of(step));

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.FAILED);

        assertThat(execution.getFailureReason())
            .isEqualTo("Unsupported executor: HTTP_REQUEST");
    }

    @Test
    void unknownExecutionIsRejected() {

        when(executionRepository.findForProcessing(21L))
            .thenReturn(Optional.empty());

        NonRetryableExecutionException ex = assertThrows(
            NonRetryableExecutionException.class,
            () -> processor.process(21L)
        );

        assertThat(ex.getMessage())
            .isEqualTo("Execution not found: 21");

        verifyNoInteractions(stepRepository);
    }

    @Test
    void runningExecutionIsRejected() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        execution.markRunning();

        when(executionRepository.findForProcessing(21L))
            .thenReturn(Optional.of(execution));

        assertThrows(
            IllegalStateException.class,
            () -> processor.process(21L)
        );

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.RUNNING);

        verifyNoInteractions(stepRepository);
    }

    @Test
    void failedDeliveryDoesNotRunStepsAgain() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        execution.markRunning();
        execution.markFailed("Missing required field: customerId");

        when(executionRepository.findForProcessing(21L))
            .thenReturn(Optional.of(execution));

        processor.process(21L);

        assertThat(execution.getFailureReason())
            .isEqualTo("Missing required field: customerId");

        verifyNoInteractions(stepRepository);
    }

    @Test
    void missingStepConfigurationFails() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        WorkflowStep step = new WorkflowStep(
            workflow,
            "Validate Order",
            StepType.VALIDATE_FIELDS,
            1
        );

        step.setConfiguration(null);

        mockProcessing(execution, List.of(step));

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.FAILED);

        assertThat(execution.getFailureReason())
            .isEqualTo("Step configuration is missing");
    }

    @Test
    void invalidRequiredFieldsFails() {

        Workflow workflow = createWorkflow();

        WorkflowExecution execution = createExecution(
            workflow,
            Map.of("orderId", 123)
        );

        WorkflowStep step = new WorkflowStep(
            workflow,
            "Validate Order",
            StepType.VALIDATE_FIELDS,
            1
        );

        mockProcessing(execution, List.of(step));

        processor.process(21L);

        assertThat(execution.getStatus())
            .isEqualTo(ExecutionStatus.FAILED);

        assertThat(execution.getFailureReason())
            .isEqualTo("Invalid requiredFields configuration");
    }

    private void mockProcessing(
            WorkflowExecution execution,
            List<WorkflowStep> steps) {

        when(executionRepository.findForProcessing(21L))
            .thenReturn(Optional.of(execution));

        when(stepRepository.findByWorkflow_IdOrderByPositionAsc(7L))
            .thenReturn(steps);
    }

    private Workflow createWorkflow() {

        Workflow workflow = new Workflow(
            "Order Processing",
            "Test workflow"
        );

        ReflectionTestUtils.setField(
            workflow, "id", 7L
        );

        return workflow;
    }

    private WorkflowExecution createExecution(
            Workflow workflow,
            Map<String, Object> payload) {

        WorkflowExecution execution =
            new WorkflowExecution(
                workflow,
                payload,
                "order-123"
            );

        execution.markQueued();

        return execution;
    }

    private WorkflowStep createValidationStep(
            Workflow workflow) {

        WorkflowStep step = new WorkflowStep(
            workflow,
            "Validate Order",
            StepType.VALIDATE_FIELDS,
            1
        );

        step.setConfiguration(Map.of(
            "requiredFields",
            List.of("orderId", "amount", "customerId")
        ));

        return step;
    }
}