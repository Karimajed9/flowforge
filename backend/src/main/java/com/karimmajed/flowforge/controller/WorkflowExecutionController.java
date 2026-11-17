package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.dto.RecentExecutionResponse;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.security.CurrentCaller;
import com.karimmajed.flowforge.service.DashboardReadService;
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

    private final DashboardReadService dashboardReads;

    private final CurrentCaller caller;

    public WorkflowExecutionController(
            WorkflowExecutionService service,
            ExecutionStepHistoryService stepHistory,
            DashboardReadService dashboardReads,
            CurrentCaller caller) {
        this.service = service;
        this.stepHistory = stepHistory;
        this.dashboardReads = dashboardReads;
        this.caller = caller;
    }

    @GetMapping("/executions")
    public Page<RecentExecutionResponse> recentExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return dashboardReads.recentExecutions(page, size, caller.require());
    }

    @GetMapping("/executions/{id}")
    public WorkflowExecutionResponse getExecution(
            @PathVariable Long id) {

        return service.getById(id, caller.require());
    }

    @GetMapping("/executions/{id}/steps")
    public List<ExecutionStepResponse> getExecutionSteps(
            @PathVariable Long id) {

        return stepHistory.getSteps(id, caller.require());
    }

    @GetMapping("/workflows/{workflowId}/executions")
    public Page<WorkflowExecutionResponse> getWorkflowExecutions(
            @PathVariable Long workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return service.getByWorkflow(
            workflowId,
            page,
            size,
            caller.require()
        );
    }
}