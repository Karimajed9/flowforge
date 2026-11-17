package com.karimmajed.flowforge.repository;

import com.karimmajed.flowforge.entity.Workflow;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WorkflowRepository
        extends JpaRepository<Workflow, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Workflow> findByWebhookTokenHashAndActiveTrue(
        String webhookTokenHash
    );

    Optional<Workflow> findByIdAndOwnerSubjectAndOwnerIssuer(
            Long id,
            String ownerSubject,
            String ownerIssuer);

    boolean existsByIdAndOwnerSubjectAndOwnerIssuer(
            Long id,
            String ownerSubject,
            String ownerIssuer);

    Page<Workflow> findByOwnerSubjectAndOwnerIssuer(
            String ownerSubject,
            String ownerIssuer,
            Pageable pageable);

    @Query("""
            SELECT w FROM Workflow w
            WHERE w.ownerSubject = :ownerSubject
              AND w.ownerIssuer = :ownerIssuer
              AND (
                    :activeFilter = 'all'
                    OR (:activeFilter = 'active' AND w.active = true)
                    OR (:activeFilter = 'inactive' AND w.active = false)
              )
              AND (
                    (:namePattern = '' AND :workflowId = 0)
                    OR (:namePattern <> '' AND LOWER(w.name) LIKE :namePattern ESCAPE '\\')
                    OR (:workflowId <> 0 AND w.id = :workflowId)
              )
            """)
    Page<Workflow> searchOwned(
            @Param("ownerSubject") String ownerSubject,
            @Param("ownerIssuer") String ownerIssuer,
            @Param("activeFilter") String activeFilter,
            @Param("namePattern") String namePattern,
            @Param("workflowId") long workflowId,
            Pageable pageable);
}