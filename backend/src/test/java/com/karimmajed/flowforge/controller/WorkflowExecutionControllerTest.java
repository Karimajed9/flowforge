package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.dto.RecentExecutionResponse;
import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.service.DashboardReadService;
import com.karimmajed.flowforge.service.ExecutionStepHistoryService;
import com.karimmajed.flowforge.service.WorkflowExecutionService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowExecutionController.class)
class WorkflowExecutionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowExecutionService service;

    @MockitoBean
    private ExecutionStepHistoryService stepHistory;

    @MockitoBean
    private DashboardReadService dashboardReads;

    @Test
    void getExecutionReturnsTheRecord() throws Exception {
        when(service.getById(eq(21L), any(ResourceOwner.class))).thenReturn(sample());

        mockMvc.perform(get("/api/executions/{id}", 21))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.workflowId").value(7))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-13T17:02:00Z"))
                .andExpect(jsonPath("$.failureReason").value(nullValue()));

        verify(service).getById(eq(21L), any(ResourceOwner.class));
    }

    @Test
    void getExecutionReturnsTheFailureReason() throws Exception {
        when(service.getById(eq(21L), any(ResourceOwner.class))).thenReturn(new WorkflowExecutionResponse(
                21L,
                7L,
                ExecutionStatus.FAILED,
                OffsetDateTime.parse("2026-11-13T17:02:00Z"),
                "Unsupported executor: HTTP_REQUEST"));

        mockMvc.perform(get("/api/executions/{id}", 21))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason")
                        .value("Unsupported executor: HTTP_REQUEST"));
    }

    @Test
    void missingExecutionIsNotFound() throws Exception {
        when(service.getById(eq(99L), any(ResourceOwner.class))).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Execution not found"));

        mockMvc.perform(get("/api/executions/{id}", 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void workflowExecutionsUseTheDefaultPage() throws Exception {
        when(service.getByWorkflow(eq(7L), eq(0), eq(20), any(ResourceOwner.class)))
                .thenReturn(new PageImpl<>(List.of(sample())));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(21))
                .andExpect(jsonPath("$.content[0].workflowId").value(7))
                .andExpect(jsonPath("$.content[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.content[0].failureReason").value(nullValue()))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(service).getByWorkflow(eq(7L), eq(0), eq(20), any(ResourceOwner.class));
    }

    @Test
    void workflowExecutionsPassPageAndSize() throws Exception {
        when(service.getByWorkflow(eq(7L), eq(2), eq(50), any(ResourceOwner.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 7)
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(service).getByWorkflow(eq(7L), eq(2), eq(50), any(ResourceOwner.class));
    }

    @Test
    void invalidPageIsBadRequest() throws Exception {
        when(service.getByWorkflow(eq(7L), eq(-1), eq(20), any(ResourceOwner.class))).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be >= 0 and size must be between 1 and 100"));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 7)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingWorkflowIsNotFound() throws Exception {
        when(service.getByWorkflow(eq(99L), eq(0), eq(20), any(ResourceOwner.class))).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonNumericExecutionIdDoesNotCallTheService() throws Exception {
        mockMvc.perform(get("/api/executions/{id}", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void getExecutionStepsReturnsCheckpointHistory() throws Exception {
        when(stepHistory.getSteps(eq(21L), any(ResourceOwner.class))).thenReturn(List.of(
                new ExecutionStepResponse(
                        4L,
                        "Order",
                        StepType.VALIDATE_FIELDS,
                        1,
                        ExecutionStepStatus.SUCCEEDED,
                        1,
                        null,
                        OffsetDateTime.parse("2026-11-15T18:00:00Z"),
                        OffsetDateTime.parse("2026-11-15T18:00:01Z"),
                        null),
                new ExecutionStepResponse(
                        8L,
                        "Notify",
                        StepType.HTTP_REQUEST,
                        2,
                        ExecutionStepStatus.FAILED,
                        1,
                        "HTTP status 404",
                        OffsetDateTime.parse("2026-11-15T18:00:01Z"),
                        OffsetDateTime.parse("2026-11-15T18:00:02Z"),
                        null),
                new ExecutionStepResponse(
                        9L,
                        "Customer",
                        StepType.VALIDATE_FIELDS,
                        3,
                        ExecutionStepStatus.NOT_STARTED,
                        null,
                        null,
                        null,
                        null,
                        null)));

        mockMvc.perform(get("/api/executions/{id}/steps", 21))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stepId").value(4))
                .andExpect(jsonPath("$[0].name").value("Order"))
                .andExpect(jsonPath("$[0].stepType").value("VALIDATE_FIELDS"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[0].failureReason").value(nullValue()))
                .andExpect(jsonPath("$[0].leaseUntil").value(nullValue()))
                .andExpect(jsonPath("$[1].leaseUntil").value(nullValue()))
                .andExpect(jsonPath("$[2].leaseUntil").value(nullValue()))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureReason").value("HTTP status 404"))
                .andExpect(jsonPath("$[2].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$[2].attemptNumber").value(nullValue()))
                .andExpect(jsonPath("$[2].startedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].attemptToken").doesNotExist())
                .andExpect(jsonPath("$[1].configuration").doesNotExist())
                .andExpect(jsonPath("$[1].body").doesNotExist());

        verify(stepHistory).getSteps(eq(21L), any(ResourceOwner.class));
        verifyNoInteractions(service);
    }

    @Test
    void missingExecutionStepsAreNotFound() throws Exception {
        when(stepHistory.getSteps(eq(99L), any(ResourceOwner.class))).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Execution not found"));

        mockMvc.perform(get("/api/executions/{id}/steps", 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonNumericExecutionStepsIdDoesNotCallTheService() throws Exception {
        mockMvc.perform(get("/api/executions/{id}/steps", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service, stepHistory);
    }

    @Test
    void recentExecutionsOmitPayloadsAndSecrets() throws Exception {
        when(dashboardReads.recentExecutions(eq(0), eq(20), any(ResourceOwner.class))).thenReturn(new PageImpl<>(List.of(
                new RecentExecutionResponse(
                        21L,
                        4L,
                        "Onboarding",
                        ExecutionStatus.FAILED,
                        OffsetDateTime.parse("2026-11-16T15:04:00Z"),
                        "downstream rejected the request"))));

        mockMvc.perform(get("/api/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(21))
                .andExpect(jsonPath("$.content[0].workflowId").value(4))
                .andExpect(jsonPath("$.content[0].workflowName").value("Onboarding"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].failureReason")
                        .value("downstream rejected the request"))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist())
                .andExpect(jsonPath("$.content[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.content[0].webhookToken").doesNotExist())
                .andExpect(jsonPath("$.content[0].webhookTokenHash").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(dashboardReads).recentExecutions(eq(0), eq(20), any(ResourceOwner.class));
        verifyNoInteractions(service, stepHistory);
    }

    @Test
    void recentExecutionsRejectAnOversizedPage() throws Exception {
        when(dashboardReads.recentExecutions(eq(0), eq(101), any(ResourceOwner.class))).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be >= 0 and size must be between 1 and 100"));

        mockMvc.perform(get("/api/executions").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runReturnsTheNewExecutionWithoutThePayload() throws Exception {
        when(service.run(eq(4L), eq("manual-1"), any(byte[].class), anyMap(), any(ResourceOwner.class)))
                .thenReturn(new WebhookTriggerResult(sample(), false));

        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .header("Idempotency-Key", "manual-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.webhookToken").doesNotExist());

        verify(service).run(eq(4L), eq("manual-1"), any(byte[].class), anyMap(), any(ResourceOwner.class));
    }

    @Test
    void runReplayReturnsOk() throws Exception {
        when(service.run(eq(4L), isNull(), any(byte[].class), anyMap(), any(ResourceOwner.class)))
                .thenReturn(new WebhookTriggerResult(sample(), true));

        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void runRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .header("Idempotency-Key", "manual-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void runRejectsAJsonArray() throws Exception {
        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1]"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void runRejectsAnInvalidIdempotencyKey() throws Exception {
        when(service.run(eq(4L), eq("bad key"), any(byte[].class), anyMap(), any(ResourceOwner.class)))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "A valid Idempotency-Key is required"));

        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .header("Idempotency-Key", "bad key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runRejectsAPayloadOverTheExistingCap() throws Exception {
        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blob\":\"" + "a".repeat(JsonObjectBody.MAX_PAYLOAD_BYTES) + "\"}"))
                .andExpect(status().isContentTooLarge());

        verifyNoInteractions(service);
    }

    private static WorkflowExecutionResponse sample() {
        return new WorkflowExecutionResponse(
                21L,
                7L,
                ExecutionStatus.RECEIVED,
                OffsetDateTime.parse("2026-11-13T17:02:00Z"));
    }
}
