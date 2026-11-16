package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.exception.NonRetryableExecutionException;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Claim;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Completion;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.StepWork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class WorkflowExecutionProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(WorkflowExecutionProcessor.class);

    private final ExecutionCheckpointStore store;

    private volatile Runnable beforeClaim = () -> {};

    private volatile Consumer<StepWork> onStepStart = work -> {};

    public WorkflowExecutionProcessor(ExecutionCheckpointStore store) {
        this.store = store;
    }

    void setBeforeClaim(Runnable beforeClaim) {
        this.beforeClaim = beforeClaim == null ? () -> {} : beforeClaim;
    }

    void setOnStepStart(Consumer<StepWork> onStepStart) {
        this.onStepStart = onStepStart == null ? work -> {} : onStepStart;
    }

    public void process(Long executionId) {
        while (true) {
            beforeClaim.run();

            Claim claim = store.claimNext(executionId);

            switch (claim.kind()) {
                case NOT_FOUND -> throw new NonRetryableExecutionException(
                        "Execution not found: " + executionId);

                case ALREADY_FINISHED -> {
                    log.info(
                            "Execution {} already completed. Skipping.",
                            executionId);
                    return;
                }

                case NO_WORK -> {
                    log.info(
                            "Execution {} failed: {}",
                            executionId,
                            claim.failureReason());
                    return;
                }

                case BUSY -> throw new IllegalStateException(
                        "Unexpected RUNNING execution: " + executionId);

                case STEP -> {
                    StepWork work = claim.work();
                    onStepStart.accept(work);

                    try {
                        executeStep(work);
                    } catch (StepFailure ex) {
                        if (!store.fail(work, ex.getMessage())) {
                            throw lostOwnership(executionId);
                        }

                        log.info(
                                "Execution {} failed: {}",
                                executionId,
                                ex.getMessage());
                        return;
                    }

                    Completion completion = store.complete(work);

                    if (completion == Completion.NOT_OWNER) {
                        throw lostOwnership(executionId);
                    }

                    if (completion == Completion.FINISHED) {
                        log.info("Execution {} succeeded", executionId);
                        return;
                    }
                }
            }
        }
    }

    private void executeStep(StepWork work) {
        log.info(
                "Executing step {}: {} ({})",
                work.stepId(),
                work.stepName(),
                work.stepType());

        if (work.stepType() == StepType.VALIDATE_FIELDS) {
            validateFields(work);
            return;
        }

        throw new StepFailure(
                "Unsupported executor: " + work.stepType());
    }

    private void validateFields(StepWork work) {
        Map<String, Object> configuration = work.configuration();

        if (configuration == null) {
            throw new StepFailure("Step configuration is missing");
        }

        Object value = configuration.get("requiredFields");

        if (!(value instanceof List<?> fields) || fields.isEmpty()) {
            throw new StepFailure("Invalid requiredFields configuration");
        }

        for (Object field : fields) {
            if (!(field instanceof String name) || name.isBlank()) {
                throw new StepFailure("Invalid required field name");
            }

            if (!work.payload().containsKey(name)) {
                throw new StepFailure("Missing required field: " + name);
            }
        }

        log.info("Validation step {} passed", work.stepId());
    }

    private static IllegalStateException lostOwnership(Long executionId) {
        return new IllegalStateException(
                "Lost attempt ownership for execution " + executionId);
    }

    private static class StepFailure extends RuntimeException {

        StepFailure(String message) {
            super(message);
        }
    }
}
