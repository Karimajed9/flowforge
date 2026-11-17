package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WebhookTokenResponse;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        when(service.rotateWebhookToken(eq(4L), any(ResourceOwner.class)))
                .thenReturn(new WebhookTokenResponse("plain-token"));

        mockMvc.perform(post("/api/workflows/{id}/webhook-token", 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("plain-token"))
                .andExpect(jsonPath("$.webhookTokenHash").doesNotExist());

        verify(service).rotateWebhookToken(eq(4L), any(ResourceOwner.class));
    }

    @Test
    void rotateWebhookToken_missingWorkflow_isNotFound() throws Exception {
        when(service.rotateWebhookToken(eq(99L), any(ResourceOwner.class)))
                .thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Workflow not found"));

        mockMvc.perform(post("/api/workflows/{id}/webhook-token", 99))
                .andExpect(status().isNotFound());
    }

    @Test
    void getWorkflowsReturnsAPage() throws Exception {
        WorkflowResponse workflow = new WorkflowResponse(
                4L,
                "Onboarding",
                null,
                true,
                OffsetDateTime.parse("2026-11-16T15:00:00Z"));
        when(service.getPage(eq(0), eq(20), any(ResourceOwner.class)))
                .thenReturn(new PageImpl<>(
                List.of(workflow),
                PageRequest.of(0, 20),
                1));

        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(4))
                .andExpect(jsonPath("$.content[0].name").value("Onboarding"))
                .andExpect(jsonPath("$.content[0].webhookTokenHash").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.number").value(0));

        verify(service).getPage(eq(0), eq(20), any(ResourceOwner.class));
    }

    @Test
    void getWorkflowsRejectsAnOversizedPage() throws Exception {
        when(service.getPage(eq(0), eq(101), any(ResourceOwner.class)))
                .thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be >= 0 and size must be between 1 and 100"));

        mockMvc.perform(get("/api/workflows").param("size", "101"))
                .andExpect(status().isBadRequest());
    }
}
