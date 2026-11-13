package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.*;
import com.karimmajed.flowforge.entity.*;
import com.karimmajed.flowforge.repository.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class WorkflowStepService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;

    public WorkflowStepService(
            WorkflowRepository workflowRepository,
            WorkflowStepRepository stepRepository) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
    }

    @Transactional
    public WorkflowStepResponse create(
            Long workflowId,
            CreateWorkflowStepRequest request) {

        Workflow workflow = workflowRepository.findById(workflowId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Workflow not found"
            ));

        if (stepRepository.existsByWorkflow_IdAndPosition(
                workflowId, request.position())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A step already exists at this position"
            );
        }

        WorkflowStep step = new WorkflowStep(
            workflow,
            request.name(),
            request.stepType(),
            request.position()
        );

        return WorkflowStepResponse.from(
            stepRepository.save(step)
        );
    }

    @Transactional(readOnly = true)
    public List<WorkflowStepResponse> getAll(Long workflowId) {

        if (!workflowRepository.existsById(workflowId)) {
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
