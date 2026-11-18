package com.karimmajed.flowforge.dto;

import java.util.List;

public record StepListChange(
        List<WorkflowStepResponse> steps,
        long stepRevision
) {
}
