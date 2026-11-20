package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.exception.NonRetryableExecutionException;
import com.karimmajed.flowforge.http.HttpGetResult;
import com.karimmajed.flowforge.http.HttpJson;
import com.karimmajed.flowforge.http.HttpsGetExecutor;
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

    private HttpsGetExecutor httpGet;

    private volatile Runnable beforeClaim = () -> {};

    private volatile Consumer<StepWork> onStepStart = work -> {};

    public WorkflowExecutionProcessor(
            ExecutionCheckpointStore store,
            HttpsGetExecutor httpGet) {

        this.store = store;
        this.httpGet = httpGet;
    }

    void setHttpGet(HttpsGetExecutor httpGet) {
        this.httpGet = httpGet;
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

                case BUSY -> {
                    log.info(
                            "Execution {} is already running. Acknowledging the duplicate delivery.",
                            executionId);
                    return;
                }

                case STEP -> {
                    StepWork work = claim.work();
                    onStepStart.accept(work);

                    Completion completion;
                    try {
                        completion = runClaimed(work);
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

    private Completion runClaimed(StepWork work) {
        if (work.stepType() == StepType.CONDITION) {
            try {
                boolean taken = ConditionEvaluator.taken(
                        work.configuration(),
                        work.payload());
                return store.completeCondition(
                        work,
                        taken,
                        ConditionEvaluator.skippedIds(work.configuration(), taken));
            } catch (ConditionEvaluator.InvalidCondition ex) {
                throw new StepFailure(ex.getMessage());
            }
        }

        executeStep(work);
        return store.complete(work);
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

        if (work.stepType() == StepType.HTTP_REQUEST) {
            executeHttp(work);
            return;
        }

        throw new StepFailure(
                "Unsupported executor: " + work.stepType());
    }

    private void executeHttp(StepWork work) {
        Map<String, Object> configuration = work.configuration();

        if (configuration == null) {
            throw new StepFailure("Step configuration is missing");
        }

        Object method = configuration.get("method");
        Object url = configuration.get("url");

        if (!(method instanceof String httpMethod) || httpMethod.isBlank()) {
            throw new StepFailure("HTTP method is missing");
        }

        if (!"GET".equals(httpMethod) && !"POST".equals(httpMethod)) {
            throw new StepFailure("Unsupported HTTP method: " + httpMethod);
        }

        if (!(url instanceof String urlString) || urlString.isBlank()) {
            throw new StepFailure("HTTP URL is missing");
        }

        HttpGetResult result = "POST".equals(httpMethod)
                ? httpGet.post(urlString, postBody(work))
                : httpGet.get(urlString);

        switch (result.outcome()) {
            case SUCCESS -> {
                return;
            }
            case HTTP_STATUS -> throw new StepFailure(
                    "HTTP status " + result.statusCode());
            case TIMEOUT -> throw new StepFailure("HTTP request timed out");
            case NETWORK -> throw new StepFailure("HTTP network failure");
            case BLOCKED -> throw new StepFailure("HTTP destination blocked");
            case RESPONSE_TOO_LARGE -> throw new StepFailure(
                    "HTTP response too large");
        }
    }

    private static byte[] postBody(StepWork work) {
        Object source = work.configuration().get("bodySource");

        if (!(source instanceof String bodySource) || bodySource.isBlank()) {
            throw new StepFailure("HTTP body source is missing");
        }

        Object value;

        if ("STATIC_JSON".equals(bodySource)) {
            if (!work.configuration().containsKey("body")) {
                throw new StepFailure("HTTP body is missing");
            }
            value = work.configuration().get("body");
        } else if ("WORKFLOW_PAYLOAD".equals(bodySource)) {
            value = work.payload() == null ? Map.of() : work.payload();
        } else {
            throw new StepFailure("Unsupported HTTP body source");
        }

        try {
            return HttpJson.bytes(value);
        } catch (IllegalArgumentException ex) {
            throw new StepFailure(ex.getMessage());
        }
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
