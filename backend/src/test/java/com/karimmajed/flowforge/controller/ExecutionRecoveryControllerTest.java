package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.security.TestAccess;
import com.karimmajed.flowforge.service.ExecutionAbandonmentService;
import com.karimmajed.flowforge.service.ExecutionRecoveryService;
import com.karimmajed.flowforge.service.WorkflowOwnership;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ExecutionRecoveryController.class,
        properties = {
                "flowforge.recovery.enabled=true",
                "flowforge.recovery.token=test-recovery-key"
        }
)
class ExecutionRecoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExecutionRecoveryService service;

    @MockitoBean
    private ExecutionAbandonmentService abandonment;

    @MockitoBean
    private WorkflowOwnership ownership;

    @Test
    void matchingKeyQueuesRecovery() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(21))
                .andExpect(jsonPath("$.message")
                        .value("Recovery queued through transactional outbox"));

        verify(service).requestRecovery(21L);
    }

    @Test
    void missingKeyIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service, ownership);
    }

    @Test
    void wrongKeyIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "other-key"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service, ownership);
    }

    @Test
    void conflictFromTheServiceIsReturned() throws Exception {
        doThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Maximum recovery attempts reached"))
                .when(service).requestRecovery(21L);

        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isConflict());
    }

    @Test
    void nonNumericIdDoesNotCallTheService() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", "abc")
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service, abandonment);
    }

    @Test
    void matchingKeyAbandonsTheRunningStep() throws Exception {
        when(abandonment.abandon(21L, 8L, 1, "worker process is gone"))
                .thenReturn(new ExecutionAbandonmentService.Result(21L, 8L, 1));

        mockMvc.perform(post("/api/admin/executions/{id}/steps/{stepId}/abandon", 21, 8)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"attemptNumber":1,"reason":"worker process is gone"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(21))
                .andExpect(jsonPath("$.stepId").value(8))
                .andExpect(jsonPath("$.attemptNumber").value(1))
                .andExpect(jsonPath("$.message").value(
                        "Step abandoned. The external outcome may be unknown. "
                                + "Confirm the worker is no longer active before abandoning."));

        verify(abandonment).abandon(21L, 8L, 1, "worker process is gone");
        verifyNoInteractions(service);
    }

    @Test
    void abandonWithoutAKeyIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/steps/{stepId}/abandon", 21, 8)
                        .with(TestAccess.admin())
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"attemptNumber":1,"reason":"worker process is gone"}
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service, abandonment, ownership);
    }
}
