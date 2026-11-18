package com.karimmajed.flowforge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReorderWorkflowStepsRequest(
        @NotNull(message = "The step list is required")
        List<Long> stepIds,

        @NotNull(message = "Revision is required")
        @Min(value = 0, message = "Revision is required")
        Long revision
) {
}
