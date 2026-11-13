package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository
        extends JpaRepository<Workflow, Long> {
}
