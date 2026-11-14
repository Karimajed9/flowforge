package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.service.WebhookService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final WebhookService service;
    private final JsonMapper jsonMapper;

    public WebhookController(
            WebhookService service,
            JsonMapper jsonMapper) {

        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkflowExecutionResponse> trigger(
            @RequestHeader(
                value = "X-Webhook-Token",
                required = false
            ) String token,

            @RequestHeader(
                value = "Idempotency-Key",
                required = false
            ) String idempotencyKey,

            HttpServletRequest request) throws IOException {

        // Validate the webhook token.
        if (token == null || token.isBlank()
                || token.length() > 128) {

            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Webhook not found"
            );
        }

        // Validate the idempotency key.
        if (idempotencyKey == null ||
                !idempotencyKey.matches(
                    "[A-Za-z0-9._:-]{1,128}"
                )) {

            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A valid Idempotency-Key is required"
            );
        }

        // Read at most 64 KiB + 1 byte.
        byte[] body = request.getInputStream()
            .readNBytes(MAX_PAYLOAD_BYTES + 1);

        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(
                HttpStatus.CONTENT_TOO_LARGE,
                "Webhook payload is too large"
            );
        }

        // Parse JSON without assuming it is an object.
        JsonNode root;

        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Malformed JSON payload",
                ex
            );
        }

        // Reject arrays, strings, numbers, null, etc.
        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A JSON object is required"
            );
        }

        // Convert the JSON object into a Java Map.
        Map<String, Object> payload =
            jsonMapper.convertValue(
                root,
                new TypeReference<Map<String, Object>>() {}
            );

        // Trigger or replay the execution.
        WebhookTriggerResult result = service.trigger(
            token,
            idempotencyKey,
            payload
        );

        // 201 = new execution
        // 200 = replay of an existing execution
        HttpStatus status = result.replayed()
            ? HttpStatus.OK
            : HttpStatus.CREATED;

        return ResponseEntity
            .status(status)
            .body(result.response());
    }
}