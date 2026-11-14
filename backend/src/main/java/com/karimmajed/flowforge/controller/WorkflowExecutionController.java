package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.service.WorkflowExecutionService;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api")
@RestController
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    public WorkflowExecutionController(
            WorkflowExecutionService service) {
        this.service = service;
    }

    @GetMapping("/executions/{id}")
    public WorkflowExecutionResponse getExecution(
            @PathVariable Long id) {

        return service.getById(id);
    }

    @GetMapping("/workflows/{workflowId}/executions")
    public Page<WorkflowExecutionResponse> getWorkflowExecutions(
            @PathVariable Long workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.getByWorkflow(
            workflowId,
            page,
            size
        );
    }
}