package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.enums.StepType;
import jakarta.validation.constraints.*;

import java.util.Map;

public record CreateWorkflowStepRequest(
    @NotBlank
    @Size(max = 150)
    String name,

    @NotNull
    StepType stepType,

    @NotNull
    @Min(1)
    Integer position,

    @NotNull
    Map<String, Object> configuration
) {
}
