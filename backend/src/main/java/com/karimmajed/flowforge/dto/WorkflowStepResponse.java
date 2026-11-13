package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.entity.WorkflowStep;
import com.karimmajed.flowforge.enums.StepType;

import java.util.Map;

public record WorkflowStepResponse(
    Long id,
    String name,
    StepType stepType,
    int position,
    Map<String, Object> configuration
) {
    public static WorkflowStepResponse from(WorkflowStep step) {
        return new WorkflowStepResponse(
            step.getId(),
            step.getName(),
            step.getStepType(),
            step.getPosition(),
            step.getConfiguration()
        );
    }
}
