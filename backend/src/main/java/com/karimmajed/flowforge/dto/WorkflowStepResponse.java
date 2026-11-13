package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.entity.WorkflowStep;
import com.karimmajed.flowforge.enums.StepType;

public record WorkflowStepResponse(
    Long id,
    String name,
    StepType stepType,
    int position
) {
    public static WorkflowStepResponse from(
            WorkflowStep step) {

        return new WorkflowStepResponse(
            step.getId(),
            step.getName(),
            step.getStepType(),
            step.getPosition()
        );
    }
}
