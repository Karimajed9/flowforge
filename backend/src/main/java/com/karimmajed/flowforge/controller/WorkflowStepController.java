package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.*;
import com.karimmajed.flowforge.security.CurrentCaller;
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

    @PostMapping
    public ResponseEntity<WorkflowStepResponse> create(
            @PathVariable Long workflowId,
            @Valid @RequestBody CreateWorkflowStepRequest request) {

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(service.create(workflowId, request, caller.require()));
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
