package com.karimmajed.flowforge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateWorkflowStepRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 150, message = "Name must be 150 characters or fewer")
        String name,

        @NotNull(message = "Configuration is required")
        Map<String, Object> configuration,

        @NotNull(message = "Revision is required")
        @Min(value = 0, message = "Revision is required")
        Long revision
) {
}
