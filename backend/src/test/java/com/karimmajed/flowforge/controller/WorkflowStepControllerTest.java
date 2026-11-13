package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.CreateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.WorkflowStepResponse;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.service.WorkflowStepService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowStepController.class)
class WorkflowStepControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowStepService service;

    @Test
    void create_returnsValidateFieldsStep() throws Exception {
        when(service.create(eq(4L), any())).thenReturn(new WorkflowStepResponse(
                10L,
                "Validate email",
                StepType.VALIDATE_FIELDS,
                1,
                Map.of("requiredFields", List.of("email"))));

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Validate email",
                                  "stepType": "VALIDATE_FIELDS",
                                  "position": 1,
                                  "configuration": { "requiredFields": ["email"] }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.name").value("Validate email"))
                .andExpect(jsonPath("$.stepType").value("VALIDATE_FIELDS"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.configuration.requiredFields[0]").value("email"));

        verify(service).create(eq(4L), any(CreateWorkflowStepRequest.class));
    }

    @Test
    void create_returnsHttpRequestStep() throws Exception {
        when(service.create(eq(4L), any())).thenReturn(new WorkflowStepResponse(
                11L,
                "Call API",
                StepType.HTTP_REQUEST,
                2,
                Map.of("method", "POST", "url", "https://example.com/hook")));

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Call API",
                                  "stepType": "HTTP_REQUEST",
                                  "position": 2,
                                  "configuration": {
                                    "method": "POST",
                                    "url": "https://example.com/hook"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.name").value("Call API"))
                .andExpect(jsonPath("$.stepType").value("HTTP_REQUEST"))
                .andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.configuration.method").value("POST"))
                .andExpect(jsonPath("$.configuration.url").value("https://example.com/hook"));

        verify(service).create(eq(4L), any(CreateWorkflowStepRequest.class));
    }

    @Test
    void getAll_returnsSteps() throws Exception {
        when(service.getAll(4L)).thenReturn(List.of(
                new WorkflowStepResponse(
                        10L,
                        "Validate email",
                        StepType.VALIDATE_FIELDS,
                        1,
                        Map.of("requiredFields", List.of("email"))),
                new WorkflowStepResponse(
                        11L,
                        "Call API",
                        StepType.HTTP_REQUEST,
                        2,
                        Map.of("method", "GET", "url", "https://example.com/hook"))));

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Validate email"))
                .andExpect(jsonPath("$[0].stepType").value("VALIDATE_FIELDS"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[1].name").value("Call API"))
                .andExpect(jsonPath("$[1].stepType").value("HTTP_REQUEST"))
                .andExpect(jsonPath("$[1].configuration.url").value("https://example.com/hook"));

        verify(service).getAll(4L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"name\":\" \",\"stepType\":\"VALIDATE_FIELDS\",\"position\":1,\"configuration\":{\"requiredFields\":[\"email\"]}}",
            "{\"name\":\"\",\"stepType\":\"HTTP_REQUEST\",\"position\":1,\"configuration\":{\"method\":\"GET\",\"url\":\"https://example.com\"}}",
            "{\"stepType\":\"VALIDATE_FIELDS\",\"position\":1,\"configuration\":{\"requiredFields\":[\"email\"]}}",
            "{\"name\":\"Check\",\"position\":1,\"configuration\":{\"requiredFields\":[\"email\"]}}",
            "{\"name\":\"Check\",\"stepType\":\"SEND_EMAIL\",\"position\":1,\"configuration\":{\"requiredFields\":[\"email\"]}}",
            "{\"name\":\"Check\",\"stepType\":\"VALIDATE_FIELDS\",\"position\":0,\"configuration\":{\"requiredFields\":[\"email\"]}}",
            "{\"name\":\"Check\",\"stepType\":\"VALIDATE_FIELDS\",\"configuration\":{\"requiredFields\":[\"email\"]}}",
            "{\"name\":\"Check\",\"stepType\":\"VALIDATE_FIELDS\",\"position\":1,\"configuration\":null}",
            "{\"name\":\"Check\",\"stepType\":\"HTTP_REQUEST\",\"position\":1}"
    })
    void create_rejectsInvalidRequest(String body) throws Exception {
        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void create_rejectsNameLongerThan150Characters() throws Exception {
        String body = "{\"name\":\"" + "n".repeat(151)
                + "\",\"stepType\":\"VALIDATE_FIELDS\",\"position\":1,\"configuration\":{\"requiredFields\":[\"email\"]}}";

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any(), any());
    }

    @Test
    void create_missingWorkflow_isNotFound() throws Exception {
        when(service.create(eq(99L), any())).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"));

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 99)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAll_missingWorkflow_isNotFound() throws Exception {
        when(service.getAll(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"));

        mockMvc.perform(get("/api/workflows/{workflowId}/steps", 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_duplicatePosition_isConflict() throws Exception {
        when(service.create(eq(4L), any())).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A step already exists at this position"));

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict());
    }

    @Test
    void create_invalidConfiguration_isBadRequest() throws Exception {
        when(service.create(eq(4L), any())).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Method must be GET or POST"));

        mockMvc.perform(post("/api/workflows/{workflowId}/steps", 4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Call API",
                                  "stepType": "HTTP_REQUEST",
                                  "position": 1,
                                  "configuration": { "method": "PUT", "url": "https://example.com" }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private static String validBody() {
        return """
                {
                  "name": "Validate email",
                  "stepType": "VALIDATE_FIELDS",
                  "position": 1,
                  "configuration": { "requiredFields": ["email"] }
                }
                """;
    }
}
