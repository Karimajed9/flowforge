package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowStepRepository
        extends JpaRepository<WorkflowStep, Long> {

    List<WorkflowStep> findByWorkflow_IdOrderByPositionAsc(
        Long workflowId
    );

    Optional<WorkflowStep> findByIdAndWorkflow_Id(Long id, Long workflowId);

    boolean existsByWorkflow_IdAndPosition(
        Long workflowId,
        int position
    );
}
