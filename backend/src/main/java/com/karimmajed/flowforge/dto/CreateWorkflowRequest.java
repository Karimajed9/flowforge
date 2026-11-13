package com.karimmajed.flowforge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWorkflowRequest(
    @NotBlank
    @Size(max = 150)
    String name,

    String description
) {
}
