package com.karimmajed.flowforge.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateWorkflowStatusRequest(
    @NotNull Boolean active
) {
}