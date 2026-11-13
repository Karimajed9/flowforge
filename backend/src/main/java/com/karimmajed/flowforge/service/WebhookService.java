package com.karimmajed.flowforge.service;

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
    public WorkflowExecutionResponse trigger(
            String token,
            Map<String, Object> payload) {

        String tokenHash = tokenService.hashToken(token);

        Workflow workflow = workflowRepository
            .findByWebhookTokenHashAndActiveTrue(tokenHash)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Webhook not found"
            ));

        WorkflowExecution execution =
            new WorkflowExecution(workflow, payload);

        WorkflowExecution saved =
            executionRepository.save(execution);

        return WorkflowExecutionResponse.from(saved);
    }
}