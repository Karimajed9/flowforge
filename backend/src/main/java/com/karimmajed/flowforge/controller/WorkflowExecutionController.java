package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.dto.RecentExecutionResponse;
import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.security.CurrentCaller;
import com.karimmajed.flowforge.service.DashboardReadService;
import com.karimmajed.flowforge.service.ExecutionStepHistoryService;
import com.karimmajed.flowforge.service.WorkflowExecutionService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RequestMapping("/api")
@RestController
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    private final ExecutionStepHistoryService stepHistory;

    private final DashboardReadService dashboardReads;

    private final CurrentCaller caller;

    private final JsonMapper jsonMapper;

    public WorkflowExecutionController(
            WorkflowExecutionService service,
            ExecutionStepHistoryService stepHistory,
            DashboardReadService dashboardReads,
            CurrentCaller caller,
            JsonMapper jsonMapper) {
        this.service = service;
        this.stepHistory = stepHistory;
        this.dashboardReads = dashboardReads;
        this.caller = caller;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(
            value = "/workflows/{workflowId}/executions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowExecutionResponse> run(
            @PathVariable Long workflowId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) throws IOException {

        byte[] body = JsonObjectBody.readLimited(request);
        Map<String, Object> payload = JsonObjectBody.parse(body, jsonMapper);
        WebhookTriggerResult result = service.run(
                workflowId,
                idempotencyKey,
                body,
                payload,
                caller.require());

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.response());
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