package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.CreateWorkflowStepRequest;
import com.karimmajed.flowforge.dto.WorkflowStepResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowStep;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.repository.WorkflowRepository;
import com.karimmajed.flowforge.repository.WorkflowStepRepository;
import com.karimmajed.flowforge.validation.StepConfigurationValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowStepServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowStepRepository stepRepository;

    @Mock
    private StepConfigurationValidator configurationValidator;

    @InjectMocks
    private WorkflowStepService service;

    @Test
    void create_savesValidatedStepAndReturnsIt() {
        Workflow workflow = new Workflow("Onboarding", "New hire");
        Map<String, Object> configuration = Map.of(
                "method", "POST",
                "url", "https://example.com/hook");
        CreateWorkflowStepRequest request = new CreateWorkflowStepRequest(
                "Notify",
                StepType.HTTP_REQUEST,
                2,
                configuration);

        when(workflowRepository.findById(8L)).thenReturn(Optional.of(workflow));
        when(stepRepository.save(any())).thenAnswer(invocation -> {
            WorkflowStep saved = invocation.getArgument(0);
            setId(saved, 15L);
            return saved;
        });

        WorkflowStepResponse response = service.create(8L, request);

        ArgumentCaptor<WorkflowStep> saved = ArgumentCaptor.forClass(WorkflowStep.class);
        InOrder order = inOrder(workflowRepository, configurationValidator, stepRepository);
        order.verify(workflowRepository).findById(8L);
        order.verify(configurationValidator).validate(StepType.HTTP_REQUEST, configuration);
        order.verify(stepRepository).existsByWorkflow_IdAndPosition(8L, 2);
        order.verify(stepRepository).save(saved.capture());

        assertThat(saved.getValue().getName()).isEqualTo("Notify");
        assertThat(saved.getValue().getStepType()).isEqualTo(StepType.HTTP_REQUEST);
        assertThat(saved.getValue().getPosition()).isEqualTo(2);
        assertThat(saved.getValue().getConfiguration()).isSameAs(configuration);
        assertThat(field(saved.getValue(), "workflow")).isSameAs(workflow);

        assertThat(response.id()).isEqualTo(15L);
        assertThat(response.name()).isEqualTo("Notify");
        assertThat(response.stepType()).isEqualTo(StepType.HTTP_REQUEST);
        assertThat(response.position()).isEqualTo(2);
        assertThat(response.configuration()).isSameAs(configuration);
    }

    @Test
    void create_missingWorkflow_isNotFoundAndDoesNotContinue() {
        when(workflowRepository.findById(8L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.create(8L, request()));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Workflow not found");
        verifyNoInteractions(configurationValidator, stepRepository);
    }

    @Test
    void create_invalidConfiguration_doesNotCheckPositionOrSave() {
        when(workflowRepository.findById(8L))
                .thenReturn(Optional.of(new Workflow("Onboarding", null)));
        doThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "requiredFields must be a non-empty array"))
                .when(configurationValidator)
                .validate(any(), any());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.create(8L, request()));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).isEqualTo("requiredFields must be a non-empty array");
        verify(stepRepository, never()).existsByWorkflow_IdAndPosition(anyLong(), anyInt());
        verify(stepRepository, never()).save(any());
    }

    @Test
    void create_duplicatePosition_isConflictAndDoesNotSave() {
        when(workflowRepository.findById(8L))
                .thenReturn(Optional.of(new Workflow("Onboarding", null)));
        when(stepRepository.existsByWorkflow_IdAndPosition(8L, 1)).thenReturn(true);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.create(8L, request()));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("A step already exists at this position");
        verify(configurationValidator).validate(StepType.VALIDATE_FIELDS, request().configuration());
        verify(stepRepository, never()).save(any());
    }

    @Test
    void getAll_mapsStepsInRepositoryOrder() {
        Workflow workflow = new Workflow("Onboarding", null);
        WorkflowStep first = step(workflow, "Validate", StepType.VALIDATE_FIELDS, 1, 4L);
        WorkflowStep second = step(workflow, "Notify", StepType.HTTP_REQUEST, 2, 5L);
        when(workflowRepository.existsById(8L)).thenReturn(true);
        when(stepRepository.findByWorkflow_IdOrderByPositionAsc(8L))
                .thenReturn(List.of(first, second));

        List<WorkflowStepResponse> responses = service.getAll(8L);

        assertThat(responses).containsExactly(
                WorkflowStepResponse.from(first),
                WorkflowStepResponse.from(second));
        verify(workflowRepository).existsById(8L);
        verify(stepRepository).findByWorkflow_IdOrderByPositionAsc(8L);
        verifyNoInteractions(configurationValidator);
    }

    @Test
    void getAll_missingWorkflow_isNotFoundAndDoesNotQuerySteps() {
        when(workflowRepository.existsById(8L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.getAll(8L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Workflow not found");
        verify(stepRepository, never()).findByWorkflow_IdOrderByPositionAsc(any());
        verifyNoInteractions(configurationValidator);
    }

    private static CreateWorkflowStepRequest request() {
        return new CreateWorkflowStepRequest(
                "Validate",
                StepType.VALIDATE_FIELDS,
                1,
                Map.of("requiredFields", List.of("email")));
    }

    private static WorkflowStep step(
            Workflow workflow,
            String name,
            StepType stepType,
            int position,
            long id) {
        WorkflowStep step = new WorkflowStep(workflow, name, stepType, position);
        step.setConfiguration(Map.of("requiredFields", List.of(name)));
        setId(step, id);
        return step;
    }

    private static void setId(WorkflowStep step, long id) {
        try {
            Field field = WorkflowStep.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(step, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
