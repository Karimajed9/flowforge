package com.karimmajed.flowforge.dto;

public record WebhookTriggerResult(
        WorkflowExecutionResponse response,
        boolean replayed
) {
}