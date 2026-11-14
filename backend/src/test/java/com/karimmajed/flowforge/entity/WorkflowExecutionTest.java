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

    @Test
    void markRunningAcceptsReceivedAndQueued() {
        WorkflowExecution received = receivedExecution();
        received.markRunning();
        assertThat(received.getStatus()).isEqualTo(ExecutionStatus.RUNNING);

        WorkflowExecution queued = receivedExecution();
        queued.markQueued();
        queued.markRunning();
        assertThat(queued.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionStatus.class,
            names = {"RUNNING", "SUCCEEDED", "FAILED"})
    void markRunningRejectsAnExecutionThatAlreadyStarted(ExecutionStatus status) {
        WorkflowExecution execution = receivedExecution();
        setField(execution, "status", status);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                execution::markRunning);

        assertThat(ex.getMessage())
                .isEqualTo("Cannot start execution from " + status);
        assertThat(execution.getStatus()).isEqualTo(status);
    }

    @Test
    void markSucceededClearsAnyFailureReason() {
        WorkflowExecution execution = runningExecution();
        setField(execution, "failureReason", "old");

        execution.markSucceeded();

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(execution.getFailureReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionStatus.class,
            names = {"RECEIVED", "QUEUED", "SUCCEEDED", "FAILED"})
    void markSucceededRejectsAnythingOtherThanRunning(ExecutionStatus status) {
        WorkflowExecution execution = receivedExecution();
        setField(execution, "status", status);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                execution::markSucceeded);

        assertThat(ex.getMessage())
                .isEqualTo("Only RUNNING executions can succeed");
        assertThat(execution.getStatus()).isEqualTo(status);
    }

    @Test
    void markFailedStoresTheReason() {
        WorkflowExecution execution = runningExecution();

        execution.markFailed("Missing required field: customerId");

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(execution.getFailureReason())
                .isEqualTo("Missing required field: customerId");
    }

    @Test
    void markFailedTruncatesAReasonLongerThan1000Characters() {
        WorkflowExecution execution = runningExecution();
        String reason = "x".repeat(1001);

        execution.markFailed(reason);

        assertThat(execution.getFailureReason()).hasSize(1000);
        assertThat(execution.getFailureReason()).isEqualTo(reason.substring(0, 1000));
    }

    @Test
    void markFailedRejectsANullReason() {
        WorkflowExecution execution = runningExecution();

        assertThrows(NullPointerException.class, () -> execution.markFailed(null));
        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(
            value = ExecutionStatus.class,
            names = {"RECEIVED", "QUEUED", "SUCCEEDED", "FAILED"})
    void markFailedRejectsAnythingOtherThanRunning(ExecutionStatus status) {
        WorkflowExecution execution = receivedExecution();
        setField(execution, "status", status);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> execution.markFailed("boom"));

        assertThat(ex.getMessage())
                .isEqualTo("Only RUNNING executions can fail");
        assertThat(execution.getStatus()).isEqualTo(status);
        assertThat(execution.getFailureReason()).isNull();
    }

    private static WorkflowExecution runningExecution() {
        WorkflowExecution execution = receivedExecution();
        execution.markRunning();
        return execution;
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
