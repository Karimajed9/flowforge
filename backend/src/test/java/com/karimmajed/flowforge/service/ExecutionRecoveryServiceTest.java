package com.karimmajed.flowforge.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionRecoveryServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private ExecutionRecoveryService service;

    @Test
    void queuedExecutionQueuesAnotherOutboxEvent() {
        stubExecution("QUEUED", 1, null);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(21L)))
                .thenReturn(0);

        service.requestRecovery(21L);

        verify(jdbc).update(contains("recovery_attempts"), eq(21L));
        verify(jdbc).update(contains("INSERT INTO execution_outbox"), eq(21L));
    }

    @Test
    void receivedExecutionCanBeRecoveredAfterTheCooldown() {
        stubExecution(
                "RECEIVED",
                2,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(21L)))
                .thenReturn(0);

        service.requestRecovery(21L);

        verify(jdbc).update(contains("INSERT INTO execution_outbox"), eq(21L));
    }

    @Test
    void missingExecutionIsNotFound() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(21L)))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.requestRecovery(21L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getReason()).isEqualTo("Execution not found");
        verify(jdbc, never()).update(anyString(), eq(21L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "SUCCEEDED", "FAILED"})
    void completedOrRunningExecutionCannotBeRecovered(String status) {
        stubExecution(status, 0, null);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.requestRecovery(21L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason())
                .isEqualTo("Execution cannot be recovered from status: " + status);
        verify(jdbc, never()).queryForObject(anyString(), eq(Integer.class), eq(21L));
        verify(jdbc, never()).update(anyString(), eq(21L));
    }

    @Test
    void thirdRecoveryIsTheLast() {
        stubExecution("QUEUED", 3, null);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.requestRecovery(21L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("Maximum recovery attempts reached");
        verify(jdbc, never()).update(anyString(), eq(21L));
    }

    @Test
    void recentRecoveryIsRejected() {
        stubExecution(
                "QUEUED",
                1,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(9));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.requestRecovery(21L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).isEqualTo("A recovery was requested recently");
        verify(jdbc, never()).update(anyString(), eq(21L));
    }

    @Test
    void pendingOutboxEventBlocksAnother() {
        stubExecution("QUEUED", 0, null);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(21L)))
                .thenReturn(1);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.requestRecovery(21L));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason())
                .isEqualTo("Execution already has a pending outbox event");
        verify(jdbc, never()).update(anyString(), eq(21L));
    }

    @SuppressWarnings("unchecked")
    private void stubExecution(
            String status,
            int attempts,
            OffsetDateTime lastRequestedAt) {

        when(jdbc.query(anyString(), any(RowMapper.class), eq(21L)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("status")).thenReturn(status);
                    when(rs.getInt("recovery_attempts")).thenReturn(attempts);
                    when(rs.getObject(
                            "last_recovery_requested_at",
                            OffsetDateTime.class))
                            .thenReturn(lastRequestedAt);
                    return List.of(mapper.mapRow(rs, 0));
                });
    }
}
