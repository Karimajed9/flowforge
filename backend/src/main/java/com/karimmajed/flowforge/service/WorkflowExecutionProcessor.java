package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.entity.WorkflowStep;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowStepRepository;
import com.karimmajed.flowforge.exception.NonRetryableExecutionException;

import org.slf4j.Logger;        
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowExecutionProcessor {

    private static final Logger log =
        LoggerFactory.getLogger(
            WorkflowExecutionProcessor.class
        );

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowStepRepository stepRepository;

    public WorkflowExecutionProcessor(
            WorkflowExecutionRepository executionRepository,
            WorkflowStepRepository stepRepository) {

        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
    }

    @Transactional
    public void process(Long executionId) {

        WorkflowExecution execution = executionRepository
            .findForProcessing(executionId)
            .orElse(null);

            if (execution == null) {
                throw new NonRetryableExecutionException(
                    "Execution not found: " + executionId
                );
            }

        // Duplicate deliveries must not execute steps again.
        if (execution.getStatus() == ExecutionStatus.SUCCEEDED
                || execution.getStatus() == ExecutionStatus.FAILED) {

            log.info(
                "Execution {} already completed. Skipping.",
                executionId
            );
            return;
        }

        // This version should not commit RUNNING independently.
        if (execution.getStatus() == ExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                "Unexpected RUNNING execution: " + executionId
            );
        }

        execution.markRunning();

        Long workflowId = execution.getWorkflow().getId();

        List<WorkflowStep> steps =
            stepRepository.findByWorkflow_IdOrderByPositionAsc(
                workflowId
            );

        try {
            if (steps.isEmpty()) {
                throw new StepFailure(
                    "Workflow contains no steps"
                );
            }

            for (WorkflowStep step : steps) {
                executeStep(step, execution.getPayload());
            }

            execution.markSucceeded();

            log.info(
                "Execution {} succeeded",
                executionId
            );

        } catch (StepFailure ex) {

            execution.markFailed(ex.getMessage());

            log.info(
                "Execution {} failed: {}",
                executionId,
                ex.getMessage()
            );
        }

        // No save() required:
        // Hibernate automatically persists changes
        // to the managed execution when the transaction commits.
    }

    private void executeStep(
            WorkflowStep step,
            Map<String, Object> payload) {

        log.info(
            "Executing step {}: {} ({})",
            step.getId(),
            step.getName(),
            step.getStepType()
        );

        if (step.getStepType() == StepType.VALIDATE_FIELDS) {
            validateFields(step, payload);
            return;
        }

        throw new StepFailure(
            "Unsupported executor: " + step.getStepType()
        );
    }

    private void validateFields(
            WorkflowStep step,
            Map<String, Object> payload) {

        Map<String, Object> configuration =
            step.getConfiguration();

        if (configuration == null) {
            throw new StepFailure(
                "Step configuration is missing"
            );
        }

        Object value = configuration.get("requiredFields");

        if (!(value instanceof List<?> fields)
                || fields.isEmpty()) {

            throw new StepFailure(
                "Invalid requiredFields configuration"
            );
        }

        for (Object field : fields) {

            if (!(field instanceof String name)
                    || name.isBlank()) {

                throw new StepFailure(
                    "Invalid required field name"
                );
            }

            if (!payload.containsKey(name)) {
                throw new StepFailure(
                    "Missing required field: " + name
                );
            }
        }

        log.info(
            "Validation step {} passed",
            step.getId()
        );
    }

    private static class StepFailure
            extends RuntimeException {

        StepFailure(String message) {
            super(message);
        }
    }
}