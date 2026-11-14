package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.WorkflowExecution;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WorkflowExecutionRepository
        extends JpaRepository<WorkflowExecution, Long> {

    Optional<WorkflowExecution>
        findByWorkflow_IdAndIdempotencyKey(
            Long workflowId,
            String idempotencyKey
        );

    Page<WorkflowExecution> findByWorkflow_Id(
            Long workflowId,
            Pageable pageable
        );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT e
        FROM WorkflowExecution e
        WHERE e.id = :id
        """)
    Optional<WorkflowExecution> findForProcessing(
        @Param("id") Long id
    );
}