package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.service.ExecutionStepHistoryService;
import com.karimmajed.flowforge.service.WorkflowExecutionService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void getExecutionReturnsTheRecord() throws Exception {
        when(service.getById(21L)).thenReturn(sample());

        mockMvc.perform(get("/api/executions/{id}", 21))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.workflowId").value(7))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-13T17:02:00Z"))
                .andExpect(jsonPath("$.failureReason").value(nullValue()));

        verify(service).getById(21L);
    }

    @Test
    void getExecutionReturnsTheFailureReason() throws Exception {
        when(service.getById(21L)).thenReturn(new WorkflowExecutionResponse(
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
        when(service.getById(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Execution not found"));

        mockMvc.perform(get("/api/executions/{id}", 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void workflowExecutionsUseTheDefaultPage() throws Exception {
        when(service.getByWorkflow(7L, 0, 20))
                .thenReturn(new PageImpl<>(List.of(sample())));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(21))
                .andExpect(jsonPath("$.content[0].workflowId").value(7))
                .andExpect(jsonPath("$.content[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$.content[0].failureReason").value(nullValue()))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(service).getByWorkflow(7L, 0, 20);
    }

    @Test
    void workflowExecutionsPassPageAndSize() throws Exception {
        when(service.getByWorkflow(7L, 2, 50))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 7)
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(service).getByWorkflow(7L, 2, 50);
    }

    @Test
    void invalidPageIsBadRequest() throws Exception {
        when(service.getByWorkflow(7L, -1, 20)).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be >= 0 and size must be between 1 and 100"));

        mockMvc.perform(get("/api/workflows/{workflowId}/executions", 7)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingWorkflowIsNotFound() throws Exception {
        when(service.getByWorkflow(99L, 0, 20)).thenThrow(new ResponseStatusException(
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
        when(stepHistory.getSteps(21L)).thenReturn(List.of(
                new ExecutionStepResponse(
                        4L,
                        "Order",
                        StepType.VALIDATE_FIELDS,
                        1,
                        ExecutionStepStatus.SUCCEEDED,
                        1,
                        null,
                        OffsetDateTime.parse("2026-11-15T18:00:00Z"),
                        OffsetDateTime.parse("2026-11-15T18:00:01Z")),
                new ExecutionStepResponse(
                        8L,
                        "Notify",
                        StepType.HTTP_REQUEST,
                        2,
                        ExecutionStepStatus.FAILED,
                        1,
                        "HTTP status 404",
                        OffsetDateTime.parse("2026-11-15T18:00:01Z"),
                        OffsetDateTime.parse("2026-11-15T18:00:02Z")),
                new ExecutionStepResponse(
                        9L,
                        "Customer",
                        StepType.VALIDATE_FIELDS,
                        3,
                        ExecutionStepStatus.NOT_STARTED,
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
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureReason").value("HTTP status 404"))
                .andExpect(jsonPath("$[2].status").value("NOT_STARTED"))
                .andExpect(jsonPath("$[2].attemptNumber").value(nullValue()))
                .andExpect(jsonPath("$[2].startedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].attemptToken").doesNotExist())
                .andExpect(jsonPath("$[1].configuration").doesNotExist())
                .andExpect(jsonPath("$[1].body").doesNotExist());

        verify(stepHistory).getSteps(21L);
        verifyNoInteractions(service);
    }

    @Test
    void missingExecutionStepsAreNotFound() throws Exception {
        when(stepHistory.getSteps(99L)).thenThrow(new ResponseStatusException(
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

    private static WorkflowExecutionResponse sample() {
        return new WorkflowExecutionResponse(
                21L,
                7L,
                ExecutionStatus.RECEIVED,
                OffsetDateTime.parse("2026-11-13T17:02:00Z"));
    }
}
