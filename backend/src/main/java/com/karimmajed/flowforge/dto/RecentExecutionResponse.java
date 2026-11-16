package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.enums.ExecutionStatus;

import java.time.OffsetDateTime;

public record RecentExecutionResponse(
        Long id,
        Long workflowId,
        String workflowName,
        ExecutionStatus status,
        OffsetDateTime createdAt,
        String failureReason
) {
}
