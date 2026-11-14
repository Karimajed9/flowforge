package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.entity.Workflow;
import com.karimmajed.flowforge.entity.WorkflowExecution;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.repository.WorkflowExecutionRepository;
import com.karimmajed.flowforge.repository.WorkflowRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @InjectMocks
    private WorkflowExecutionService service;

    @Test
    void getById_returnsTheExecution() {
        WorkflowExecution execution = execution(21L);

        when(executionRepository.findById(21L))
                .thenReturn(Optional.of(execution));

        WorkflowExecutionResponse response = service.getById(21L);

        assertThat(response.id()).isEqualTo(21L);
        assertThat(response.workflowId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(ExecutionStatus.RECEIVED);
        assertThat(response.createdAt())
                .isEqualTo(OffsetDateTime.parse("2026-11-13T17:02:00Z"));
        assertThat(response.failureReason()).isNull();
    }

    @Test
    void getById_returnsTheFailureReason() {
        WorkflowExecution execution = execution(21L);
        execution.markQueued();
        execution.markRunning();
        execution.markFailed("Missing required field: customerId");

        when(executionRepository.findById(21L))
                .thenReturn(Optional.of(execution));

        WorkflowExecutionResponse response = service.getById(21L);

        assertThat(response.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(response.failureReason())
                .isEqualTo("Missing required field: customerId");
    }

    @Test
    void getById_missingExecution_isNotFound() {
        when(executionRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.getById(99L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Execution not found");
    }

    @Test
    void getByWorkflow_returnsNewestPage() {
        WorkflowExecution execution = execution(21L);
        when(workflowRepository.existsById(7L)).thenReturn(true);
        when(executionRepository.findByWorkflow_Id(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(execution)));

        Page<WorkflowExecutionResponse> page = service.getByWorkflow(7L, 1, 20);

        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(21L);
            assertThat(row.workflowId()).isEqualTo(7L);
            assertThat(row.status()).isEqualTo(ExecutionStatus.RECEIVED);
        });

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        InOrder order = inOrder(workflowRepository, executionRepository);
        order.verify(workflowRepository).existsById(7L);
        order.verify(executionRepository).findByWorkflow_Id(eq(7L), pageable.capture());

        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().stream().map(Sort.Order::getProperty))
                .containsExactly("createdAt", "id");
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending())
                .isTrue();
        assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending())
                .isTrue();
    }

    @Test
    void getByWorkflow_missingWorkflow_isNotFoundAndDoesNotQueryExecutions() {
        when(workflowRepository.existsById(99L)).thenReturn(false);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.getByWorkflow(99L, 0, 20));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Workflow not found");
        verify(executionRepository, never()).findByWorkflow_Id(any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 20",
            "0, 0",
            "0, -1",
            "0, 101"
    })
    void getByWorkflow_rejectsPageAndSizeOutsideTheAllowedRange(int page, int size) {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.getByWorkflow(7L, page, size));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason())
                .isEqualTo("Page must be >= 0 and size must be between 1 and 100");
        verifyNoInteractions(workflowRepository, executionRepository);
    }

    private static WorkflowExecution execution(long id) {
        Workflow workflow = new Workflow("Orders", "Order webhook workflow");
        setField(workflow, "id", 7L);
        WorkflowExecution execution = new WorkflowExecution(
                workflow,
                Map.of("amount", 150),
                "order-123");
        setField(execution, "id", id);
        setField(execution, "createdAt", OffsetDateTime.parse("2026-11-13T17:02:00Z"));
        return execution;
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
}
