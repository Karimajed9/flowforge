package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.service.ExecutionStepHistoryService;
import com.karimmajed.flowforge.service.WorkflowExecutionService;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequestMapping("/api")
@RestController
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    private final ExecutionStepHistoryService stepHistory;

    public WorkflowExecutionController(
            WorkflowExecutionService service,
            ExecutionStepHistoryService stepHistory) {
        this.service = service;
        this.stepHistory = stepHistory;
    }

    @GetMapping("/executions/{id}")
    public WorkflowExecutionResponse getExecution(
            @PathVariable Long id) {

        return service.getById(id);
    }

    @GetMapping("/executions/{id}/steps")
    public List<ExecutionStepResponse> getExecutionSteps(
            @PathVariable Long id) {

        return stepHistory.getSteps(id);
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