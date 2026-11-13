package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.service.WebhookService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

    private final WebhookService service;

    public WebhookController(WebhookService service) {
        this.service = service;
    }

    @PostMapping("/{token}")
    public ResponseEntity<WorkflowExecutionResponse> trigger(
            @PathVariable String token,
            @RequestBody Map<String, Object> payload) {

        WorkflowExecutionResponse response =
            service.trigger(token, payload);

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(response);
    }
}