package com.karimmajed.flowforge.messaging;

import com.karimmajed.flowforge.exception.NonRetryableExecutionException;
import com.karimmajed.flowforge.service.WorkflowExecutionProcessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionMessageListenerTest {

    @Mock
    private WorkflowExecutionProcessor processor;

    @InjectMocks
    private ExecutionMessageListener listener;

    @Test
    void validMessageCallsProcessor() {

        listener.receive(" 21 ");

        verify(processor).process(21L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "-1", "invalid-id", "21.5"})
    void invalidMessageIsPermanentFailure(String body) {

        NonRetryableExecutionException ex = assertThrows(
            NonRetryableExecutionException.class,
            () -> listener.receive(body)
        );

        assertThat(ex.getMessage())
            .isEqualTo("Invalid execution ID in RabbitMQ message");

        verifyNoInteractions(processor);
    }

    @Test
    void processorFailurePropagates() {

        NonRetryableExecutionException failure =
            new NonRetryableExecutionException(
                "Execution not found: 21"
            );

        doThrow(failure).when(processor).process(21L);

        NonRetryableExecutionException ex = assertThrows(
            NonRetryableExecutionException.class,
            () -> listener.receive("21")
        );

        assertThat(ex).isSameAs(failure);
    }
}