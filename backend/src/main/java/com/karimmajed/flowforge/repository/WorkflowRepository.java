package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowRepository
        extends JpaRepository<Workflow, Long> {

    Optional<Workflow> findByWebhookTokenHashAndActiveTrue(
        String webhookTokenHash
    );
}
