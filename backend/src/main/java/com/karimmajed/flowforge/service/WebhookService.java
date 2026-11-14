package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class WebhookService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutionRepository executionRepository;
    private final WebhookTokenService tokenService;

    public WebhookService(
            WorkflowRepository workflowRepository,
            WorkflowExecutionRepository executionRepository,
            WebhookTokenService tokenService) {

        this.workflowRepository = workflowRepository;
        this.executionRepository = executionRepository;
        this.tokenService = tokenService;
    }

    @Transactional
    public WebhookTriggerResult trigger(
            String token,
            String idempotencyKey,
            Map<String, Object> payload) {

        // Hash the raw token.
        String tokenHash = tokenService.hashToken(token);

        // Find the active workflow and lock its row.
        Workflow workflow = workflowRepository
            .findByWebhookTokenHashAndActiveTrue(tokenHash)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Webhook not found"
            ));

        // Check if this event has already been received.
        var existing = executionRepository
            .findByWorkflow_IdAndIdempotencyKey(
                workflow.getId(),
                idempotencyKey
            );

        if (existing.isPresent()) {

            WorkflowExecution previous = existing.get();

            // Same key but different payload is not allowed.
            if (!previous.getPayload().equals(payload)) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key was already used " +
                    "with a different payload"
                );
            }

            // Same event: reuse the existing execution.
            return new WebhookTriggerResult(
                WorkflowExecutionResponse.from(previous),
                true
            );
        }

        // New event: create an execution.
        WorkflowExecution execution =
            new WorkflowExecution(
                workflow,
                payload,
                idempotencyKey
            );

        WorkflowExecution saved =
            executionRepository.save(execution);

        return new WebhookTriggerResult(
            WorkflowExecutionResponse.from(saved),
            false
        );
    }
}