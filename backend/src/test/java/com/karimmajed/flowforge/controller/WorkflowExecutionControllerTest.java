package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
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

    private static WorkflowExecutionResponse sample() {
        return new WorkflowExecutionResponse(
                21L,
                7L,
                ExecutionStatus.RECEIVED,
                OffsetDateTime.parse("2026-11-13T17:02:00Z"));
    }
}
