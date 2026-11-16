package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;

import java.time.OffsetDateTime;

public record ExecutionStepResponse(
        Long stepId,
        String name,
        StepType stepType,
        int position,
        ExecutionStepStatus status,
        Integer attemptNumber,
        String failureReason,
        OffsetDateTime startedAt,
        OffsetDateTime updatedAt
) {}
