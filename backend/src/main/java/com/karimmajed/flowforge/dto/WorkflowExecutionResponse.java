package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.enums.ExecutionStatus;

import java.time.OffsetDateTime;

public record WorkflowExecutionResponse(
    Long id,
    Long workflowId,
    ExecutionStatus status,
    OffsetDateTime createdAt
) {
    public static WorkflowExecutionResponse from(
            WorkflowExecution execution) {

        return new WorkflowExecutionResponse(
            execution.getId(),
            execution.getWorkflow().getId(),
            execution.getStatus(),
            execution.getCreatedAt()
        );
    }
}