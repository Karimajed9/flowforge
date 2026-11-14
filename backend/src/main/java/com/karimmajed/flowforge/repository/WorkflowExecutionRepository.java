package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.WorkflowExecution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowExecutionRepository
        extends JpaRepository<WorkflowExecution, Long> {

    Optional<WorkflowExecution> findByWorkflow_IdAndIdempotencyKey(
        Long workflowId,
        String idempotencyKey
    );
}