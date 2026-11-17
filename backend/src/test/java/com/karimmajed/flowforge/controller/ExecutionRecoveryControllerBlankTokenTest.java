package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.security.TestAccess;
import com.karimmajed.flowforge.service.ExecutionAbandonmentService;
import com.karimmajed.flowforge.service.ExecutionRecoveryService;
import com.karimmajed.flowforge.service.WorkflowOwnership;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ExecutionRecoveryController.class,
        properties = {
                "flowforge.recovery.enabled=true",
                "flowforge.recovery.token="
        }
)
class ExecutionRecoveryControllerBlankTokenTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExecutionRecoveryService service;

    @MockitoBean
    private ExecutionAbandonmentService abandonment;

    @MockitoBean
    private WorkflowOwnership ownership;

    @Test
    void blankConfiguredTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }
}
