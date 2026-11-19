package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;

import java.time.OffsetDateTime;

/**
 * Step history for one execution. {@code leaseUntil} is informational.
 * A past timestamp does not prove the worker has stopped.
 */
public record ExecutionStepResponse(
        Long stepId,
        String name,
        StepType stepType,
        int position,
        ExecutionStepStatus status,
        Integer attemptNumber,
        String failureReason,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt,
        OffsetDateTime leaseUntil,
        Boolean branchTaken
) {}
