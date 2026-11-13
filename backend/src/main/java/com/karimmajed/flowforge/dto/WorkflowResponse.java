package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.entity.Workflow;
import java.time.OffsetDateTime;

public record WorkflowResponse(
    Long id,
    String name,
    String description,
    boolean active,
    OffsetDateTime createdAt
) {
    public static WorkflowResponse from(Workflow workflow) {
        return new WorkflowResponse(
            workflow.getId(),
            workflow.getName(),
            workflow.getDescription(),
            workflow.isActive(),
            workflow.getCreatedAt()
        );
    }
}
