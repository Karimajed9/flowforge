package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.*;
import com.karimmajed.flowforge.service.WorkflowStepService;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows/{workflowId}/steps")
public class WorkflowStepController {

    private final WorkflowStepService service;

    public WorkflowStepController(WorkflowStepService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WorkflowStepResponse> create(
            @PathVariable Long workflowId,
            @Valid @RequestBody CreateWorkflowStepRequest request) {

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(service.create(workflowId, request));
    }

    @GetMapping
    public List<WorkflowStepResponse> getAll(
            @PathVariable Long workflowId) {

        return service.getAll(workflowId);
    }
}
