package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WebhookTriggerResult;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WebhookService service;

    private WorkflowExecutionResponse exampleResponse() {
        return new WorkflowExecutionResponse(
            21L,
            7L,
            ExecutionStatus.RECEIVED,
            OffsetDateTime.parse("2026-11-13T17:02:00Z")
        );
    }

    @Test
    void newWebhookReturns201() throws Exception {

        when(service.trigger(
            eq("valid-token"),
            eq("order-123"),
            anyMap()
        )).thenReturn(new WebhookTriggerResult(
            exampleResponse(),
            false
        ));

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":150}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(21))
            .andExpect(jsonPath("$.workflowId").value(7))
            .andExpect(jsonPath("$.status").value("RECEIVED"));

        verify(service).trigger(
            eq("valid-token"),
            eq("order-123"),
            anyMap()
        );
    }

    @Test
    void replayReturns200() throws Exception {

        when(service.trigger(
            eq("valid-token"),
            eq("order-123"),
            anyMap()
        )).thenReturn(new WebhookTriggerResult(
            exampleResponse(),
            true
        ));

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":150}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(21));
    }

    @Test
    void missingTokenReturns404() throws Exception {

        mockMvc.perform(post("/api/webhooks")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());

        verifyNoInteractions(service);
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonReturns400() throws Exception {

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[1,2,3]",
        "\"hello\"",
        "123",
        "true",
        "null"
    })
    void nonObjectJsonReturns400(String body) throws Exception {

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void oversizedPayloadReturns413() throws Exception {

        String largePayload =
            "{\"data\":\"" + "a".repeat(65536) + "\"}";

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(largePayload))
            .andExpect(status().is(413));

        verifyNoInteractions(service);
    }

    @Test
    void differentPayloadWithSameKeyReturns409() throws Exception {

        when(service.trigger(
            eq("valid-token"),
            eq("order-123"),
            anyMap()
        )).thenThrow(new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Idempotency key was already used with a different payload"
        ));

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":200}"))
            .andExpect(status().isConflict());
    }

    @Test
    void validJsonObjectPassesThrough() throws Exception {

        when(service.trigger(
            eq("valid-token"),
            eq("order-123"),
            anyMap()
        )).thenReturn(new WebhookTriggerResult(
            exampleResponse(),
            false
        ));

        mockMvc.perform(post("/api/webhooks")
                .header("X-Webhook-Token", "valid-token")
                .header("Idempotency-Key", "order-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountt\":150}"))
            .andExpect(status().isCreated());

        verify(service).trigger(
            eq("valid-token"),
            eq("order-123"),
            argThat(payload ->
                Integer.valueOf(150).equals(payload.get("amountt"))
            )
        );
    }
}