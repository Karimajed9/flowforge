package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.exception.NonRetryableExecutionException;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Claim;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.Completion;
import com.karimmajed.flowforge.service.ExecutionCheckpointStore.StepWork;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionProcessorTest {

    @Mock
    private ExecutionCheckpointStore store;

    @InjectMocks
    private WorkflowExecutionProcessor processor;

    @Test
    void validPayloadSucceeds() {
        StepWork work = validationWork(
                List.of("orderId", "amount", "customerId"),
                Map.of("orderId", 123, "amount", 150, "customerId", 45));

        when(store.claimNext(21L)).thenReturn(Claim.step(work));
        when(store.complete(work)).thenReturn(Completion.FINISHED);

        processor.process(21L);

        verify(store).complete(work);
        verify(store, never()).fail(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingRequiredFieldFails() {
        StepWork work = validationWork(
                List.of("orderId", "amount", "customerId"),
                Map.of("orderId", 123, "amount", 150));

        when(store.claimNext(21L)).thenReturn(Claim.step(work));
        when(store.fail(work, "Missing required field: customerId")).thenReturn(true);

        processor.process(21L);

        verify(store).fail(work, "Missing required field: customerId");
        verify(store, never()).complete(work);
    }

    @Test
    void duplicateDeliveryDoesNotRunStepsAgain() {
        when(store.claimNext(21L)).thenReturn(Claim.alreadyFinished());

        processor.process(21L);

        verify(store, never()).complete(org.mockito.ArgumentMatchers.any());
        verify(store, never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyWorkflowFails() {
        when(store.claimNext(21L))
                .thenReturn(Claim.noWork(ExecutionCheckpointStore.NO_STEPS));

        processor.process(21L);

        verify(store, never()).complete(org.mockito.ArgumentMatchers.any());
        verify(store, never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unsupportedHttpStepFails() {
        StepWork work = new StepWork(
                21L,
                8L,
                "Send HTTP Request",
                StepType.HTTP_REQUEST,
                Map.of("method", "GET", "url", "https://example.com"),
                Map.of("orderId", 123),
                "attempt-1",
                1);

        when(store.claimNext(21L)).thenReturn(Claim.step(work));
        when(store.fail(work, "Unsupported executor: HTTP_REQUEST")).thenReturn(true);

        processor.process(21L);

        verify(store).fail(work, "Unsupported executor: HTTP_REQUEST");
        verify(store, never()).complete(work);
    }

    @Test
    void unknownExecutionIsRejected() {
        when(store.claimNext(21L)).thenReturn(Claim.notFound());

        NonRetryableExecutionException ex = assertThrows(
                NonRetryableExecutionException.class,
                () -> processor.process(21L));

        assertThat(ex.getMessage()).isEqualTo("Execution not found: 21");
    }

    @Test
    void runningExecutionIsRejected() {
        when(store.claimNext(21L)).thenReturn(Claim.busy());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> processor.process(21L));

        assertThat(ex.getMessage()).isEqualTo("Unexpected RUNNING execution: 21");
        verify(store, never()).complete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedDeliveryDoesNotRunStepsAgain() {
        when(store.claimNext(21L)).thenReturn(Claim.alreadyFinished());

        processor.process(21L);

        verify(store, never()).complete(org.mockito.ArgumentMatchers.any());
        verify(store, never()).fail(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingStepConfigurationFails() {
        StepWork work = new StepWork(
                21L,
                8L,
                "Validate Order",
                StepType.VALIDATE_FIELDS,
                null,
                Map.of("orderId", 123),
                "attempt-1",
                1);

        when(store.claimNext(21L)).thenReturn(Claim.step(work));
        when(store.fail(work, "Step configuration is missing")).thenReturn(true);

        processor.process(21L);

        verify(store).fail(work, "Step configuration is missing");
    }

    @Test
    void invalidRequiredFieldsFails() {
        StepWork work = validationWork(List.of(), Map.of("orderId", 123));

        when(store.claimNext(21L)).thenReturn(Claim.step(work));
        when(store.fail(work, "Invalid requiredFields configuration")).thenReturn(true);

        processor.process(21L);

        verify(store).fail(work, "Invalid requiredFields configuration");
    }

    @Test
    void lostOwnershipDoesNotReportSuccess() {
        StepWork work = validationWork(
                List.of("orderId"),
                Map.of("orderId", 123));

        when(store.claimNext(21L)).thenReturn(Claim.step(work));
        when(store.complete(work)).thenReturn(Completion.NOT_OWNER);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> processor.process(21L));

        assertThat(ex.getMessage()).isEqualTo("Lost attempt ownership for execution 21");
    }

    @Test
    void laterStepRunsOnlyAfterTheEarlierStepIsSaved() {
        StepWork first = validationWork(
                List.of("orderId"),
                Map.of("orderId", 123, "customerId", 45));
        StepWork second = new StepWork(
                21L,
                9L,
                "Validate Customer",
                StepType.VALIDATE_FIELDS,
                Map.of("requiredFields", List.of("customerId")),
                Map.of("orderId", 123, "customerId", 45),
                "attempt-2",
                1);

        when(store.claimNext(21L)).thenReturn(Claim.step(first), Claim.step(second));
        when(store.complete(first)).thenReturn(Completion.CONTINUE);
        when(store.complete(second)).thenReturn(Completion.FINISHED);

        processor.process(21L);

        verify(store).complete(first);
        verify(store).complete(second);
    }

    private static StepWork validationWork(
            List<String> requiredFields,
            Map<String, Object> payload) {

        return new StepWork(
                21L,
                8L,
                "Validate Order",
                StepType.VALIDATE_FIELDS,
                Map.of("requiredFields", requiredFields),
                payload,
                "attempt-1",
                1);
    }
}
