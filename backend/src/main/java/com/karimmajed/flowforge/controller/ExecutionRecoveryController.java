package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.service.ExecutionRecoveryService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@ConditionalOnProperty(
    prefix = "flowforge.recovery",
    name = "enabled",
    havingValue = "true"
)
public class ExecutionRecoveryController {

    private final ExecutionRecoveryService service;
    private final String expectedKey;

    public ExecutionRecoveryController(
            ExecutionRecoveryService service,
            @Value("${flowforge.recovery.token:}")
            String expectedKey) {

        this.service = service;
        this.expectedKey = expectedKey;
    }

    @PostMapping("/api/admin/executions/{id}/recover")
    public ResponseEntity<Map<String, Object>> recover(
            @PathVariable long id,
            @RequestHeader(
                value = "X-Recovery-Key",
                required = false
            )
            String providedKey) {

        if (expectedKey.isBlank()
                || providedKey == null
                || !MessageDigest.isEqual(
                    expectedKey.getBytes(
                        StandardCharsets.UTF_8
                    ),
                    providedKey.getBytes(
                        StandardCharsets.UTF_8
                    )
                )) {

            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Invalid recovery credentials"
            );
        }

        service.requestRecovery(id);

        return ResponseEntity.accepted().body(
            Map.of(
                "executionId", id,
                "message",
                "Recovery queued through transactional outbox"
            )
        );
    }
}