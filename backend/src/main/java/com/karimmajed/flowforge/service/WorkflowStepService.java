package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.*;
import com.karimmajed.flowforge.entity.*;
import com.karimmajed.flowforge.exception.StepChangeException;
import com.karimmajed.flowforge.repository.*;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.validation.StepConfigurationValidator;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;

@Service
public class WorkflowStepService {

    private static final String CONFLICT_TITLE = "Step definition conflict";

    private static final String STALE =
            "This workflow changed. Refresh and try again.";

    private static final String LIST_MISMATCH =
            "The step list does not match this workflow.";

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final StepConfigurationValidator configurationValidator;
    private final JdbcTemplate jdbc;

    public WorkflowStepService(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository,
            StepConfigurationValidator configurationValidator,
            JdbcTemplate jdbc) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.configurationValidator = configurationValidator;
        this.jdbc = jdbc;
    }

    @Transactional
    public WorkflowStepResponse create(
            Long workflowId,
            CreateWorkflowStepRequest request,
            ResourceOwner owner) {

        Workflow workflow = workflowRepository
            .findByIdAndOwnerSubjectAndOwnerIssuer(
                    workflowId, owner.subject(), owner.issuer())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Workflow not found"
            ));

        configurationValidator.validate(
            request.stepType(),
            request.configuration()
        );

        if (stepRepository.existsByWorkflow_IdAndPosition(
                workflowId, request.position())) {
            throw new StepChangeException(
                    HttpStatus.CONFLICT,
                    "Workflow step position conflict",
                    "A step already exists at this position");
        }

        WorkflowStep step = new WorkflowStep(
            workflow,
            request.name(),
            request.stepType(),
            request.position()
        );

        step.setConfiguration(request.configuration());

        stepRepository.save(step);
        advance(workflow);

        return WorkflowStepResponse.from(step);
    }

    @Transactional
    public StepChange update(
            Long workflowId,
            Long stepId,
            UpdateWorkflowStepRequest request,
            ResourceOwner owner) {

        Workflow workflow = owned(workflowId, owner);
        long revision = advance(workflow, request.revision());
        WorkflowStep step = stepRepository
                .findByIdAndWorkflow_Id(stepId, workflowId)
                .orElseThrow(() -> notFound("Step not found"));

        String name = request.name().trim();
        if (name.isEmpty() || name.length() > 150) {
            throw invalid("Name is required");
        }

        try {
            configurationValidator.validate(step.getStepType(), request.configuration());
        } catch (ResponseStatusException ex) {
            throw invalid(ex.getReason());
        }

        step.setName(name);
        step.setConfiguration(request.configuration());
        return new StepChange(WorkflowStepResponse.from(step), revision);
    }

    @Transactional
    public StepListChange delete(
            Long workflowId,
            Long stepId,
            long revision,
            ResourceOwner owner) {

        Workflow workflow = owned(workflowId, owner);
        long next = advance(workflow, revision);
        WorkflowStep step = stepRepository
                .findByIdAndWorkflow_Id(stepId, workflowId)
                .orElseThrow(() -> notFound("Step not found"));

        stepRepository.delete(step);
        stepRepository.flush();
        renumber(workflowId);
        return new StepListChange(currentSteps(workflowId), next);
    }

    @Transactional
    public StepListChange reorder(
            Long workflowId,
            ReorderWorkflowStepsRequest request,
            ResourceOwner owner) {

        Workflow workflow = owned(workflowId, owner);
        long next = advance(workflow, request.revision());
        List<Long> requested = request.stepIds() == null
                ? List.of()
                : request.stepIds();
        for (Long stepId : requested) {
            if (stepId == null) {
                throw conflict(LIST_MISMATCH);
            }
        }

        List<Long> current = jdbc.query(
                """
                SELECT id
                FROM workflow_steps
                WHERE workflow_id = ?
                ORDER BY position ASC, id ASC
                """,
                (rs, rowNum) -> rs.getLong("id"),
                workflowId);

        if (!sameSteps(current, requested)) {
            throw conflict(LIST_MISMATCH);
        }

        deferPositionUniqueness();
        for (int index = 0; index < requested.size(); index++) {
            int updated = jdbc.update("""
                    UPDATE workflow_steps
                    SET position = ?
                    WHERE id = ?
                      AND workflow_id = ?
                    """,
                    index + 1,
                    requested.get(index),
                    workflowId);
            if (updated != 1) {
                throw conflict(LIST_MISMATCH);
            }
        }

        return new StepListChange(currentSteps(workflowId), next);
    }

    private List<WorkflowStepResponse> currentSteps(Long workflowId) {
        return stepRepository
                .findByWorkflow_IdOrderByPositionAsc(workflowId)
                .stream()
                .map(WorkflowStepResponse::from)
                .toList();
    }

    private void renumber(Long workflowId) {
        deferPositionUniqueness();
        jdbc.update("""
                WITH ordered AS (
                    SELECT id,
                           ROW_NUMBER() OVER (ORDER BY position, id) AS next_position
                    FROM workflow_steps
                    WHERE workflow_id = ?
                )
                UPDATE workflow_steps AS step
                SET position = CAST(ordered.next_position AS INTEGER)
                FROM ordered
                WHERE step.id = ordered.id
                  AND step.position <> ordered.next_position
                """,
                workflowId);
    }

    private void deferPositionUniqueness() {
        jdbc.execute("SET CONSTRAINTS uq_workflow_step_position DEFERRED");
    }

    private boolean sameSteps(List<Long> current, List<Long> requested) {
        if (requested.size() != current.size()) {
            return false;
        }
        if (new HashSet<>(requested).size() != requested.size()) {
            return false;
        }
        return new HashSet<>(requested).equals(new HashSet<>(current));
    }

    private Workflow owned(Long workflowId, ResourceOwner owner) {
        return workflowRepository
                .findByIdAndOwnerSubjectAndOwnerIssuer(
                        workflowId, owner.subject(), owner.issuer())
                .orElseThrow(() -> notFound("Workflow not found"));
    }

    private long advance(Workflow workflow) {
        return advance(workflow, workflow.getStepRevision());
    }

    private long advance(Workflow workflow, long expected) {
        if (workflow.getStepRevision() != expected) {
            throw conflict(STALE);
        }
        long next = expected + 1;
        int updated = jdbc.update("""
                UPDATE workflows
                SET step_revision = ?
                WHERE id = ?
                  AND step_revision = ?
                  AND owner_subject = ?
                  AND owner_issuer = ?
                """,
                next,
                workflow.getId(),
                expected,
                workflow.getOwnerSubject(),
                workflow.getOwnerIssuer());
        if (updated != 1) {
            if (!workflowRepository.existsByIdAndOwnerSubjectAndOwnerIssuer(
                    workflow.getId(),
                    workflow.getOwnerSubject(),
                    workflow.getOwnerIssuer())) {
                throw notFound("Workflow not found");
            }
            throw conflict(STALE);
        }
        return next;
    }

    private static StepChangeException invalid(String detail) {
        return new StepChangeException(
                HttpStatus.BAD_REQUEST,
                "Invalid step",
                detail == null ? "The step is invalid" : detail);
    }

    private static StepChangeException conflict(String detail) {
        return new StepChangeException(
                HttpStatus.CONFLICT,
                CONFLICT_TITLE,
                detail);
    }

    private static ResponseStatusException notFound(String reason) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
    }

    @Transactional(readOnly = true)
    public List<WorkflowStepResponse> getAll(Long workflowId, ResourceOwner owner) {

        if (!workflowRepository.existsByIdAndOwnerSubjectAndOwnerIssuer(
                workflowId, owner.subject(), owner.issuer())) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Workflow not found"
            );
        }

        return stepRepository
            .findByWorkflow_IdOrderByPositionAsc(workflowId)
            .stream()
            .map(WorkflowStepResponse::from)
            .toList();
    }
}
