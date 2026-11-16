package com.karimmajed.flowforge.controller;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ExecutionRecoveryController.class,
        properties = "flowforge.recovery.enabled=false"
)
class ExecutionRecoveryControllerClosedTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void recoveryRouteIsAbsentWhenDisabled() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isNotFound());
    }
}
