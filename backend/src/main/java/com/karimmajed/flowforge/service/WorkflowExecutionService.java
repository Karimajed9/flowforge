package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowRepository;
import com.karimmajed.flowforge.security.ResourceOwner;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class WorkflowExecutionService {

    private final WorkflowExecutionRepository executionRepository;
    private final WorkflowRepository workflowRepository;
    private final WebhookService webhookService;

    public WorkflowExecutionService(
            WorkflowExecutionRepository executionRepository,
            WorkflowRepository workflowRepository,
            WebhookService webhookService) {

        this.executionRepository = executionRepository;
        this.workflowRepository = workflowRepository;
        this.webhookService = webhookService;
    }

    @Transactional
    public WebhookTriggerResult run(
            Long workflowId,
            String clientKey,
            byte[] body,
            Map<String, Object> payload,
            ResourceOwner owner) {

        Workflow workflow = workflowRepository
                .findByIdAndOwnerSubjectAndOwnerIssuer(
                        workflowId, owner.subject(), owner.issuer())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Workflow not found"));

        if (!workflow.isActive()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Workflow is inactive");
        }

        String idempotencyKey = IdempotencyKeys.resolve(workflowId, clientKey, body);
        return webhookService.accept(workflow, idempotencyKey, payload);
    }

    @Transactional(readOnly = true)
    public WorkflowExecutionResponse getById(Long id, ResourceOwner owner) {

        WorkflowExecution execution = executionRepository
            .findByIdAndWorkflow_OwnerSubjectAndWorkflow_OwnerIssuer(
                    id, owner.subject(), owner.issuer())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Execution not found"
            ));

        return WorkflowExecutionResponse.from(execution);
    }

    @Transactional(readOnly = true)
    public Page<WorkflowExecutionResponse> getByWorkflow(
            Long workflowId,
            int page,
            int size,
            ResourceOwner owner) {

        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be >= 0 and size must be between 1 and 100"
            );
        }

        // Verify that the workflow exists.
        if (!workflowRepository.existsByIdAndOwnerSubjectAndOwnerIssuer(
                workflowId, owner.subject(), owner.issuer())) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"
            );
        }

        PageRequest pageable = PageRequest.of(
            page,
            size,
            Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
            )
        );

        return executionRepository
            .findByWorkflow_Id(workflowId, pageable)
            .map(WorkflowExecutionResponse::from);
    }
}