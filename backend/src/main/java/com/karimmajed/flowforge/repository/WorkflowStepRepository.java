package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.WorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkflowStepRepository
        extends JpaRepository<WorkflowStep, Long> {

    List<WorkflowStep> findByWorkflow_IdOrderByPositionAsc(
        Long workflowId
    );

    boolean existsByWorkflow_IdAndPosition(
        Long workflowId,
        int position
    );
}
