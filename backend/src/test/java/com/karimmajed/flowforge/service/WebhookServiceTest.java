package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    void newWebhookCreatesExecution() {

        Workflow workflow = makeWorkflow();
        Map<String, Object> payload = Map.of("amount", 150);

        mockActiveWorkflow(workflow);

        when(executionRepository
            .findByWorkflow_IdAndIdempotencyKey(
                7L, "order-123"
            ))
            .thenReturn(Optional.empty());

        when(executionRepository.save(any()))
            .thenAnswer(invocation -> {

                WorkflowExecution execution =
                    invocation.getArgument(0);

                setField(execution, "id", 21L);
                setField(
                    execution,
                    "createdAt",
                    OffsetDateTime.parse(
                        "2026-11-13T17:02:00Z"
                    )
                );

                return execution;
            });

        WebhookTriggerResult result = service.trigger(
            "raw-token",
            "order-123",
            payload
        );

        assertThat(result.replayed()).isFalse();
        assertThat(result.response().id()).isEqualTo(21L);
        assertThat(result.response().status())
            .isEqualTo(ExecutionStatus.RECEIVED);

        verify(executionRepository).save(argThat(
            execution ->
                execution.getIdempotencyKey()
                    .equals("order-123")
                && execution.getPayload().equals(payload)
        ));
    }

    @Test
    void duplicateWebhookReturnsExistingExecution() {

        Workflow workflow = makeWorkflow();
        Map<String, Object> payload = Map.of("amount", 150);

        WorkflowExecution existing =
            makeExecution(workflow, payload);

        mockActiveWorkflow(workflow);

        when(executionRepository
            .findByWorkflow_IdAndIdempotencyKey(
                7L, "order-123"
            ))
            .thenReturn(Optional.of(existing));

        WebhookTriggerResult result = service.trigger(
            "raw-token",
            "order-123",
            payload
        );

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().id()).isEqualTo(21L);

        verify(executionRepository, never()).save(any());
    }

    @Test
    void sameKeyDifferentPayloadReturns409() {

        Workflow workflow = makeWorkflow();

        WorkflowExecution existing = makeExecution(
            workflow,
            Map.of("amount", 150)
        );

        mockActiveWorkflow(workflow);

        when(executionRepository
            .findByWorkflow_IdAndIdempotencyKey(
                7L, "order-123"
            ))
            .thenReturn(Optional.of(existing));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.trigger(
                "raw-token",
                "order-123",
                Map.of("amount", 200)
            )
        );

        assertThat(exception.getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        verify(executionRepository, never()).save(any());
    }

    @Test
    void missingWorkflowReturns404() {

        when(tokenService.hashToken("missing"))
            .thenReturn("missing-hash");

        when(workflowRepository
            .findByWebhookTokenHashAndActiveTrue(
                "missing-hash"
            ))
            .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.trigger(
                "missing",
                "order-123",
                Map.of()
            )
        );

        assertThat(exception.getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(executionRepository);
    }

    private Workflow makeWorkflow() {

        Workflow workflow = new Workflow(
            "Orders",
            "Order webhook workflow"
        );

        setField(workflow, "id", 7L);

        return workflow;
    }

    private WorkflowExecution makeExecution(
            Workflow workflow,
            Map<String, Object> payload) {

        WorkflowExecution execution =
            new WorkflowExecution(
                workflow,
                payload,
                "order-123"
            );

        setField(execution, "id", 21L);
        setField(
            execution,
            "createdAt",
            OffsetDateTime.parse("2026-11-13T17:02:00Z")
        );

        return execution;
    }

    private void mockActiveWorkflow(Workflow workflow) {

        when(tokenService.hashToken("raw-token"))
            .thenReturn("token-hash");

        when(workflowRepository
            .findByWebhookTokenHashAndActiveTrue(
                "token-hash"
            ))
            .thenReturn(Optional.of(workflow));
    }

    private static void setField(
            Object target,
            String name,
            Object value) {

        try {
            Field field =
                target.getClass().getDeclaredField(name);

            field.setAccessible(true);
            field.set(target, value);

        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}