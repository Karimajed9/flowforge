package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.dto.ExecutionStepResponse;
import com.karimmajed.flowforge.enums.ExecutionStepStatus;
import com.karimmajed.flowforge.enums.StepType;
import com.karimmajed.flowforge.security.ResourceOwner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionStepHistoryServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ResultSet row;

    @InjectMocks
    private ExecutionStepHistoryService service;

    private static final ResourceOwner OWNER =
            new ResourceOwner("user", "http://auth.test/realms/flowforge");

    @Test
    void missingCheckpointIsNotStarted() throws Exception {
        stubStep(null);

        ExecutionStepResponse response = ExecutionStepHistoryService.mapRow(row);

        assertThat(response.stepId()).isEqualTo(8L);
        assertThat(response.name()).isEqualTo("Notify");
        assertThat(response.stepType()).isEqualTo(StepType.HTTP_REQUEST);
        assertThat(response.position()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(ExecutionStepStatus.NOT_STARTED);
        assertThat(response.attemptNumber()).isNull();
        assertThat(response.failureReason()).isNull();
        assertThat(response.startedAt()).isNull();
        assertThat(response.updatedAt()).isNull();
        assertThat(response.leaseUntil()).isNull();
        verify(row, never()).getString("attempt_token");
        verify(row, never()).getString("configuration");
    }

    @Test
    void runningCheckpointKeepsTheAttemptAndStartTime() throws Exception {
        OffsetDateTime started = OffsetDateTime.parse("2026-11-15T18:00:00Z");
        OffsetDateTime lease = OffsetDateTime.parse("2026-11-15T18:02:00Z");
        stubStep("RUNNING");
        when(row.getObject("attempt_number", Integer.class)).thenReturn(1);
        when(row.getString("failure_reason")).thenReturn(null);
        when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(started);
        when(row.getObject("updated_at", OffsetDateTime.class)).thenReturn(started);
        when(row.getObject("lease_until", OffsetDateTime.class)).thenReturn(lease);

        ExecutionStepResponse response = ExecutionStepHistoryService.mapRow(row);

        assertThat(response.status()).isEqualTo(ExecutionStepStatus.RUNNING);
        assertThat(response.attemptNumber()).isEqualTo(1);
        assertThat(response.failureReason()).isNull();
        assertThat(response.startedAt()).isEqualTo(started);
        assertThat(response.updatedAt()).isEqualTo(started);
        assertThat(response.leaseUntil()).isEqualTo(lease);
    }

    @Test
    void succeededCheckpointHasNoFailureReason() throws Exception {
        stubStep("SUCCEEDED");
        when(row.getObject("attempt_number", Integer.class)).thenReturn(1);
        when(row.getString("failure_reason")).thenReturn(null);
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-11-15T18:00:00Z"));
        when(row.getObject("updated_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-11-15T18:00:01Z"));

        ExecutionStepResponse response = ExecutionStepHistoryService.mapRow(row);

        assertThat(response.status()).isEqualTo(ExecutionStepStatus.SUCCEEDED);
        assertThat(response.failureReason()).isNull();
        assertThat(response.updatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-11-15T18:00:01Z"));
    }

    @Test
    void failedCheckpointKeepsTheReason() throws Exception {
        stubStep("FAILED");
        when(row.getObject("attempt_number", Integer.class)).thenReturn(1);
        when(row.getString("failure_reason")).thenReturn("HTTP status 500");
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-11-15T18:00:00Z"));
        when(row.getObject("updated_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-11-15T18:00:02Z"));

        ExecutionStepResponse response = ExecutionStepHistoryService.mapRow(row);

        assertThat(response.status()).isEqualTo(ExecutionStepStatus.FAILED);
        assertThat(response.attemptNumber()).isEqualTo(1);
        assertThat(response.failureReason()).isEqualTo("HTTP status 500");
    }

    @Test
    void missingExecutionDoesNotReadSteps() {
        when(jdbc.queryForObject(
                anyString(),
                eq(Integer.class),
                eq(99L),
                eq(OWNER.subject()),
                eq(OWNER.issuer())))
                .thenReturn(0);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.getSteps(99L, OWNER));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Execution not found");
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
    }

    private void stubStep(String status) throws Exception {
        when(row.getString("status")).thenReturn(status);
        when(row.getLong("step_id")).thenReturn(8L);
        when(row.getString("name")).thenReturn("Notify");
        when(row.getString("step_type")).thenReturn("HTTP_REQUEST");
        when(row.getInt("position")).thenReturn(2);
    }
}
