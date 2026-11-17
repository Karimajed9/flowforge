package com.karimmajed.flowforge.security;

import com.karimmajed.flowforge.controller.WorkflowController;
import com.karimmajed.flowforge.service.WorkflowService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = WorkflowController.class,
        properties = {
                "FLOWFORGE_OIDC_ISSUER_URI=http://127.0.0.1:1/realms/flowforge",
                "FLOWFORGE_OIDC_AUDIENCE=flowforge-api",
                "FLOWFORGE_OIDC_PUBLIC_KEY_LOCATION="
        }
)
class IssuerUnavailableSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowService workflows;

    @Test
    void applicationStartsAndRejectsATokenWhenTheIssuerIsDown() throws Exception {
        mockMvc.perform(get("/api/workflows"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/workflows")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
