package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.repository.WorkflowRepository;
import com.karimmajed.flowforge.dto.WebhookTokenResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class WorkflowService {

    private final WorkflowRepository repository;
    private final WebhookTokenService webhookTokenService;

    public WorkflowService(WorkflowRepository repository, WebhookTokenService tokenService) {
        this.repository = repository;
        this.webhookTokenService = tokenService;
    }

    @Transactional
    public WorkflowResponse create(CreateWorkflowRequest request) {
        Workflow workflow = new Workflow(
            request.name(),
            request.description()
        );

        Workflow saved = repository.save(workflow);
        return WorkflowResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> getAll() {
        return repository.findAll()
            .stream()
            .map(WorkflowResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getById(Long id) {
        Workflow workflow = findWorkflow(id);
        return WorkflowResponse.from(workflow);
    }

    @Transactional
    public WorkflowResponse updateStatus(
            Long id,
            UpdateWorkflowStatusRequest request) {

        Workflow workflow = findWorkflow(id);

        workflow.setActive(request.active());

        return WorkflowResponse.from(workflow);
    }

    private Workflow findWorkflow(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"
            ));
    }

    @Transactional
    public WebhookTokenResponse rotateWebhookToken(Long workflowId) {

        Workflow workflow = findWorkflow(workflowId);

        String token = webhookTokenService.generateToken();

        workflow.setWebhookTokenHash(
            webhookTokenService.hashToken(token)
        );

        return new WebhookTokenResponse(token);
    }
}