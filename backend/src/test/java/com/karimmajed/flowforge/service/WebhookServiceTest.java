package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowRepository;
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
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WebhookTokenService tokenService;

    @InjectMocks
    private WebhookService service;

    @Test
    void trigger_savesReceivedExecutionForTheHashedToken() {
        Workflow workflow = new Workflow("Onboarding", "New hire");
        setId(workflow, 7L);
        Map<String, Object> payload = Map.of("email", "a@b.c");
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-13T17:02:00Z");

        when(tokenService.hashToken("raw-token")).thenReturn("token-hash");
        when(workflowRepository.findByWebhookTokenHashAndActiveTrue("token-hash"))
                .thenReturn(Optional.of(workflow));
        when(executionRepository.save(any())).thenAnswer(invocation -> {
            WorkflowExecution saved = invocation.getArgument(0);
            setId(saved, 21L);
            setField(saved, "createdAt", createdAt);
            return saved;
        });

        WorkflowExecutionResponse response = service.trigger("raw-token", payload);

        ArgumentCaptor<WorkflowExecution> saved = ArgumentCaptor.forClass(WorkflowExecution.class);
        InOrder order = inOrder(tokenService, workflowRepository, executionRepository);
        order.verify(tokenService).hashToken("raw-token");
        order.verify(workflowRepository).findByWebhookTokenHashAndActiveTrue("token-hash");
        order.verify(executionRepository).save(saved.capture());

        assertThat(saved.getValue().getWorkflow()).isSameAs(workflow);
        assertThat(saved.getValue().getStatus()).isEqualTo(ExecutionStatus.RECEIVED);
        assertThat(field(saved.getValue(), "payload")).isSameAs(payload);

        assertThat(response.id()).isEqualTo(21L);
        assertThat(response.workflowId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ExecutionStatus.RECEIVED);
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void trigger_whenNoActiveWorkflowMatches_isNotFoundAndDoesNotSave() {
        when(tokenService.hashToken("missing")).thenReturn("token-hash");
        when(workflowRepository.findByWebhookTokenHashAndActiveTrue("token-hash"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.trigger("missing", Map.of()));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Webhook not found");
        verify(workflowRepository).findByWebhookTokenHashAndActiveTrue("token-hash");
        verify(executionRepository, never()).save(any());
    }

    private static void setId(Object target, long id) {
        setField(target, "id", id);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field declared = target.getClass().getDeclaredField(name);
            declared.setAccessible(true);
            return declared.get(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
