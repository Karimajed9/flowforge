package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WebhookTokenResponse;
import com.karimmajed.flowforge.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowService service;

    @Test
    void rotateWebhookToken_returnsThePlaintextToken() throws Exception {
        when(service.rotateWebhookToken(4L)).thenReturn(new WebhookTokenResponse("plain-token"));

        mockMvc.perform(post("/api/workflows/{id}/webhook-token", 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("plain-token"))
                .andExpect(jsonPath("$.webhookTokenHash").doesNotExist());

        verify(service).rotateWebhookToken(4L);
    }

    @Test
    void rotateWebhookToken_missingWorkflow_isNotFound() throws Exception {
        when(service.rotateWebhookToken(99L)).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"));

        mockMvc.perform(post("/api/workflows/{id}/webhook-token", 99))
                .andExpect(status().isNotFound());
    }
}
