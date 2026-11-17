package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.repository.WorkflowRepository;
import com.karimmajed.flowforge.dto.WebhookTokenResponse;
import com.karimmajed.flowforge.security.ResourceOwner;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class WorkflowService {

    private final WorkflowRepository repository;
    private final WebhookTokenService webhookTokenService;

    public WorkflowService(WorkflowRepository repository, WebhookTokenService tokenService) {
        this.repository = repository;
        this.webhookTokenService = tokenService;
    }

    @Transactional
    public WorkflowResponse create(CreateWorkflowRequest request, ResourceOwner owner) {
        Workflow workflow = new Workflow(
            request.name(),
            request.description()
        );
        workflow.assignOwner(owner.subject(), owner.issuer());

        Workflow saved = repository.save(workflow);
        return WorkflowResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<WorkflowResponse> getPage(int page, int size, ResourceOwner owner) {
        return getPage(page, size, null, null, owner);
    }

    @Transactional(readOnly = true)
    public Page<WorkflowResponse> getPage(
            int page,
            int size,
            String query,
            Boolean active,
            ResourceOwner owner) {

        PageRequest pageable = pageRequest(page, size);
        String normalized = normalizeQuery(query);
        if (normalized == null && active == null) {
            return repository
                .findByOwnerSubjectAndOwnerIssuer(owner.subject(), owner.issuer(), pageable)
                .map(WorkflowResponse::from);
        }

        String activeFilter = active == null ? "all" : active ? "active" : "inactive";
        Long workflowId = exactId(normalized);
        return repository
            .searchOwned(
                owner.subject(),
                owner.issuer(),
                activeFilter,
                namePattern(normalized),
                workflowId == null ? 0L : workflowId,
                pageable)
            .map(WorkflowResponse::from);
    }

    private static PageRequest pageRequest(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be >= 0 and size must be between 1 and 100"
            );
        }
        return PageRequest.of(
            page,
            size,
            Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
            )
        );
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 150) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Search must be at most 150 characters"
            );
        }
        return trimmed;
    }

    private static Long exactId(String query) {
        if (query == null || !query.matches("[1-9]\\d{0,18}")) {
            return null;
        }
        try {
            return Long.parseLong(query);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String namePattern(String query) {
        if (query == null) {
            return "";
        }
        String escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getById(Long id, ResourceOwner owner) {
        Workflow workflow = findWorkflow(id, owner);
        return WorkflowResponse.from(workflow);
    }

    @Transactional
    public WorkflowResponse updateStatus(
            Long id,
            UpdateWorkflowStatusRequest request,
            ResourceOwner owner) {

        Workflow workflow = findWorkflow(id, owner);

        workflow.setActive(request.active());

        return WorkflowResponse.from(workflow);
    }

    private Workflow findWorkflow(Long id, ResourceOwner owner) {
        return repository
            .findByIdAndOwnerSubjectAndOwnerIssuer(id, owner.subject(), owner.issuer())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"
            ));
    }

    @Transactional
    public WebhookTokenResponse rotateWebhookToken(Long workflowId, ResourceOwner owner) {

        Workflow workflow = findWorkflow(workflowId, owner);

        String token = webhookTokenService.generateToken();

        workflow.setWebhookTokenHash(
            webhookTokenService.hashToken(token)
        );

        return new WebhookTokenResponse(token);
    }
}