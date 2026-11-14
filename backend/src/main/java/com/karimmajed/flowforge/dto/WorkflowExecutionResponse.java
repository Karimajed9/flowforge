package com.karimmajed.flowforge.dto;

import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.enums.ExecutionStatus;

import java.time.OffsetDateTime;

public record WorkflowExecutionResponse(
        Long id,
        Long workflowId,
        ExecutionStatus status,
        OffsetDateTime createdAt,
        String failureReason
) {

    public WorkflowExecutionResponse(
            Long id,
            Long workflowId,
            ExecutionStatus status,
            OffsetDateTime createdAt) {

        this(id, workflowId, status, createdAt, null);
    }

    public static WorkflowExecutionResponse from(
            WorkflowExecution execution) {

        return new WorkflowExecutionResponse(
            execution.getId(),
            execution.getWorkflow().getId(),
            execution.getStatus(),
            execution.getCreatedAt(),
            execution.getFailureReason()
        );
    }
}