package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.security.CurrentCaller;
import com.karimmajed.flowforge.service.WorkflowService;
import com.karimmajed.flowforge.dto.WebhookTokenResponse;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService service;
    private final CurrentCaller caller;

    public WorkflowController(WorkflowService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(
            @Valid @RequestBody CreateWorkflowRequest request) {

        WorkflowResponse response = service.create(request, caller.require());

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response);
    }

    @GetMapping
    public Page<WorkflowResponse> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.getPage(page, size, caller.require());
    }

    @GetMapping("/{id}")
    public WorkflowResponse getById(@PathVariable Long id) {
        return service.getById(id, caller.require());
    }

    @PatchMapping("/{id}/status")
    public WorkflowResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWorkflowStatusRequest request) {

        return service.updateStatus(id, request, caller.require());
    }

    @PostMapping("/{id}/webhook-token")
    public WebhookTokenResponse rotateWebhookToken(
            @PathVariable Long id) {

        return service.rotateWebhookToken(id, caller.require());
    }
}