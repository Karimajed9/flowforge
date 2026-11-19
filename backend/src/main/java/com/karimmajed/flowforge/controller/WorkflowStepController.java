package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.*;
import com.karimmajed.flowforge.security.CurrentCaller;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.service.WorkflowStepService;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows/{workflowId}/steps")
public class WorkflowStepController {

    private final WorkflowStepService service;
    private final CurrentCaller caller;

    public WorkflowStepController(WorkflowStepService service, CurrentCaller caller) {
        this.service = service;
        this.caller = caller;
    }

    public ResponseEntity<WorkflowStepResponse> create(
            Long workflowId,
            CreateWorkflowStepRequest request) {
        return create(workflowId, null, request);
    }

    @PostMapping
    public ResponseEntity<WorkflowStepResponse> create(
            @PathVariable Long workflowId,
            @RequestParam(required = false) Long revision,
            @Valid @RequestBody CreateWorkflowStepRequest request) {

        ResourceOwner owner = caller.require();
        WorkflowStepResponse created = revision == null
                ? service.create(workflowId, request, owner)
                : service.create(workflowId, request, revision, owner);
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(created);
    }

    @GetMapping
    public List<WorkflowStepResponse> getAll(
            @PathVariable Long workflowId) {

        return service.getAll(workflowId, caller.require());
    }

    @PatchMapping("/{stepId}")
    public StepChange update(
            @PathVariable Long workflowId,
            @PathVariable Long stepId,
            @Valid @RequestBody UpdateWorkflowStepRequest request) {

        return service.update(workflowId, stepId, request, caller.require());
    }

    @DeleteMapping("/{stepId}")
    public StepListChange delete(
            @PathVariable Long workflowId,
            @PathVariable Long stepId,
            @RequestParam long revision) {

        return service.delete(workflowId, stepId, revision, caller.require());
    }

    @PutMapping("/order")
    public StepListChange reorder(
            @PathVariable Long workflowId,
            @Valid @RequestBody ReorderWorkflowStepsRequest request) {

        return service.reorder(workflowId, request, caller.require());
    }
}
