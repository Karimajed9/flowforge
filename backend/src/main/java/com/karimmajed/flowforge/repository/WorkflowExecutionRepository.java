package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowExecutionRepository
        extends JpaRepository<WorkflowExecution, Long> {
}