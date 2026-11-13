package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.CreateWorkflowRequest;
import com.karimmajed.flowforge.dto.UpdateWorkflowStatusRequest;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.service.WorkflowService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(
            @Valid @RequestBody CreateWorkflowRequest request) {

        WorkflowResponse response = service.create(request);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response);
    }

    @GetMapping
    public List<WorkflowResponse> getAll() {
        return service.getAll();
    }

    @GetMapping("/{id}")
    public WorkflowResponse getById(@PathVariable Long id) {
        return service.getById(id);
    }

    @PatchMapping("/{id}/status")
    public WorkflowResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWorkflowStatusRequest request) {

        return service.updateStatus(id, request);
    }
}
