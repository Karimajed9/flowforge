package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.Workflow;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface WorkflowRepository
        extends JpaRepository<Workflow, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Workflow> findByWebhookTokenHashAndActiveTrue(
        String webhookTokenHash
    );
}