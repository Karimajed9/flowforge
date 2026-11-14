package com.karimmajed.flowforge.entity;

import com.karimmajed.flowforge.enums.ExecutionStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowExecutionTest {

    @Test
    void markQueuedMovesReceivedToQueued() {
        WorkflowExecution execution = receivedExecution();

        execution.markQueued();

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.QUEUED);
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionStatus.class,
            names = {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED"})
    void markQueuedRejectsAnythingOtherThanReceived(ExecutionStatus status) {
        WorkflowExecution execution = receivedExecution();
        setField(execution, "status", status);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                execution::markQueued);

        assertThat(ex.getMessage())
                .isEqualTo("Only RECEIVED executions can become QUEUED");
        assertThat(execution.getStatus()).isEqualTo(status);
    }

    private static WorkflowExecution receivedExecution() {
        Workflow workflow = new Workflow("Orders", "Order webhook workflow");
        return new WorkflowExecution(
                workflow,
                Map.of("amount", 150),
                "order-123");
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
