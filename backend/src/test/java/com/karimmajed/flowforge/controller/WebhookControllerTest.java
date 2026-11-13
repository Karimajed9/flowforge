package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.service.WebhookService;
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

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookService service;

    @Test
    void trigger_returnsCreatedExecution() throws Exception {
        when(service.trigger(eq("abc_token-1"), any())).thenReturn(new WorkflowExecutionResponse(
                21L,
                7L,
                ExecutionStatus.RECEIVED,
                OffsetDateTime.parse("2026-11-13T17:02:00Z")));

        mockMvc.perform(post("/api/webhooks/{token}", "abc_token-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.workflowId").value(7))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-13T17:02:00Z"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> payload =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(service).trigger(eq("abc_token-1"), payload.capture());
        assertThat(payload.getValue()).containsEntry("email", "a@b.c");
    }

    @Test
    void trigger_unknownToken_isNotFound() throws Exception {
        when(service.trigger(eq("missing"), any())).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Webhook not found"));

        mockMvc.perform(post("/api/webhooks/{token}", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        verify(service).trigger(eq("missing"), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json"})
    void trigger_rejectsUnreadableBody(String body) throws Exception {
        mockMvc.perform(post("/api/webhooks/{token}", "abc_token-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
