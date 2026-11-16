package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.AbandonRunningStepRequest;
import com.karimmajed.flowforge.service.ExecutionAbandonmentService;
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
    private final ExecutionAbandonmentService abandonment;
    private final String expectedKey;

    public ExecutionRecoveryController(
            ExecutionRecoveryService service,
            ExecutionAbandonmentService abandonment,
            @Value("${flowforge.recovery.token:}")
            String expectedKey) {

        this.service = service;
        this.abandonment = abandonment;
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

        requireRecoveryKey(providedKey);

        service.requestRecovery(id);

        return ResponseEntity.accepted().body(
            Map.of(
                "executionId", id,
                "message",
                "Recovery queued through transactional outbox"
            )
        );
    }

    /**
     * Marks one RUNNING checkpoint and its execution FAILED.
     * The operator must confirm the worker is no longer active.
     * An expired lease is not accepted as that confirmation, and this
     * action does not call HTTP or requeue the execution.
     */
    @PostMapping("/api/admin/executions/{id}/steps/{stepId}/abandon")
    public ResponseEntity<Map<String, Object>> abandon(
            @PathVariable long id,
            @PathVariable long stepId,
            @RequestHeader(
                value = "X-Recovery-Key",
                required = false
            )
            String providedKey,
            @RequestBody AbandonRunningStepRequest request) {

        requireRecoveryKey(providedKey);

        if (request == null || request.attemptNumber() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Attempt number must be positive");
        }

        ExecutionAbandonmentService.Result result = abandonment.abandon(
                id,
                stepId,
                request.attemptNumber(),
                request.reason());

        return ResponseEntity.ok(Map.of(
                "executionId", result.executionId(),
                "stepId", result.stepId(),
                "attemptNumber", result.attemptNumber(),
                "message",
                "Step abandoned. The external outcome may be unknown. "
                        + "Confirm the worker is no longer active before abandoning."
        ));
    }

    private void requireRecoveryKey(String providedKey) {
        if (expectedKey.isBlank()
                || providedKey == null
                || !MessageDigest.isEqual(
                    expectedKey.getBytes(StandardCharsets.UTF_8),
                    providedKey.getBytes(StandardCharsets.UTF_8)
                )) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Invalid recovery credentials"
            );
        }
    }
}