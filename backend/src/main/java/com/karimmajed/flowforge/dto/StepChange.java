package com.karimmajed.flowforge.dto;

public record StepChange(
        WorkflowStepResponse step,
        long stepRevision
) {
}
