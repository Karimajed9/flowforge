package com.karimmajed.flowforge.security;

import com.karimmajed.flowforge.controller.DashboardController;
import com.karimmajed.flowforge.controller.ExecutionRecoveryController;
import com.karimmajed.flowforge.controller.WebhookController;
import com.karimmajed.flowforge.controller.WorkflowController;
import com.karimmajed.flowforge.controller.WorkflowExecutionController;
import com.karimmajed.flowforge.dto.DashboardOverviewResponse;
import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.service.DashboardReadService;
import com.karimmajed.flowforge.service.ExecutionAbandonmentService;
import com.karimmajed.flowforge.service.ExecutionRecoveryService;
import com.karimmajed.flowforge.service.ExecutionStepHistoryService;
import com.karimmajed.flowforge.security.ResourceOwner;
import com.karimmajed.flowforge.service.WebhookService;
import com.karimmajed.flowforge.service.WorkflowOwnership;
import com.karimmajed.flowforge.service.WorkflowExecutionService;
import com.karimmajed.flowforge.service.WorkflowService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {
                WorkflowController.class,
                DashboardController.class,
                WorkflowExecutionController.class,
                ExecutionRecoveryController.class,
                WebhookController.class
        },
        properties = {
                "flowforge.recovery.enabled=true",
                "flowforge.recovery.token=test-recovery-key"
        }
)
class ApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowService workflows;

    @MockitoBean
    private DashboardReadService dashboardReads;

    @MockitoBean
    private WorkflowExecutionService executions;

    @MockitoBean
    private ExecutionStepHistoryService stepHistory;

    @MockitoBean
    private ExecutionRecoveryService recovery;

    @MockitoBean
    private ExecutionAbandonmentService abandonment;

    @MockitoBean
    private WebhookService webhooks;

    @MockitoBean
    private WorkflowOwnership ownership;

    @Test
    void unauthenticatedApiRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/workflows").with(TestAccess.clearAuthentication()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard/overview").with(TestAccess.clearAuthentication()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/executions/{id}", 21).with(TestAccess.clearAuthentication()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .with(TestAccess.clearAuthentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(workflows, dashboardReads, executions);
    }

    @Test
    void userCanCallAPermittedApi() throws Exception {
        when(workflows.getPage(eq(0), eq(20), any(ResourceOwner.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(dashboardReads.overview(any(ResourceOwner.class))).thenReturn(new DashboardOverviewResponse(
                1, 1, 0, 0, 0, null, 7));
        when(executions.getById(eq(21L), any(ResourceOwner.class)))
                .thenReturn(new WorkflowExecutionResponse(
                21L,
                4L,
                ExecutionStatus.SUCCEEDED,
                OffsetDateTime.parse("2026-11-16T15:04:00Z")));

        mockMvc.perform(get("/api/workflows").with(TestAccess.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/api/dashboard/overview").with(TestAccess.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(1));
        mockMvc.perform(get("/api/executions/{id}", 21).with(TestAccess.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(21));

        when(executions.run(eq(4L), eq("manual-1"), any(byte[].class), anyMap(), any(ResourceOwner.class)))
                .thenReturn(new WebhookTriggerResult(
                        new WorkflowExecutionResponse(
                                21L,
                                4L,
                                ExecutionStatus.RECEIVED,
                                OffsetDateTime.parse("2026-11-16T15:04:00Z")),
                        false));
        mockMvc.perform(post("/api/workflows/{id}/executions", 4)
                        .with(TestAccess.user())
                        .header("Idempotency-Key", "manual-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    @Test
    void validTokenWithoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/api/workflows").with(TestAccess.openIdOnly()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.user())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(workflows, recovery, ownership);
    }

    @Test
    void administratorCanCallAnAdminApiWhenTheRecoveryKeyMatches() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(21));

        verify(recovery).requestRecovery(21L);
        verify(ownership).requireExecution(eq(21L), any(ResourceOwner.class));
    }

    @Test
    void administratorCannotRecoverAnotherUsersExecution() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"))
                .when(ownership).requireExecution(eq(21L), any(ResourceOwner.class));

        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(recovery);
    }

    @Test
    void administratorStillNeedsTheRecoveryKey() throws Exception {
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.admin()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(recovery, ownership);
    }

    @Test
    void keycloakRealmRoleGrantsUserAccessButNotAdmin() throws Exception {
        when(workflows.getPage(eq(0), eq(20), any(ResourceOwner.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/workflows").with(TestAccess.realmUser()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(TestAccess.realmUser())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(recovery);
    }

    @Test
    void invalidIssuerIsRejected() throws Exception {
        String token = TestAccess.signedToken(
                "http://evil.example/realms/other",
                TestAccess.AUDIENCE,
                "flowforge.user");

        mockMvc.perform(get("/api/workflows")
                        .with(TestAccess.clearAuthentication())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(workflows);
    }

    @Test
    void invalidAudienceIsRejected() throws Exception {
        String token = TestAccess.signedToken(
                TestAccess.ISSUER,
                "another-api",
                "flowforge.user");

        mockMvc.perform(get("/api/workflows")
                        .with(TestAccess.clearAuthentication())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(workflows);
    }

    @Test
    void signedUserTokenIsAccepted() throws Exception {
        when(workflows.getPage(eq(0), eq(20), any(ResourceOwner.class)))
                .thenReturn(new PageImpl<>(List.of()));
        String token = TestAccess.signedToken(
                TestAccess.ISSUER,
                TestAccess.AUDIENCE,
                "flowforge.user");

        mockMvc.perform(get("/api/workflows")
                        .with(TestAccess.clearAuthentication())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void webhookWithValidTokenDoesNotRequireJwt() throws Exception {
        when(webhooks.trigger(eq("valid-token"), eq("order-123"), anyMap()))
                .thenReturn(new WebhookTriggerResult(
                        new WorkflowExecutionResponse(
                                21L,
                                7L,
                                ExecutionStatus.RECEIVED,
                                OffsetDateTime.parse("2026-11-16T15:04:00Z")),
                        false));

        mockMvc.perform(post("/api/webhooks")
                        .with(TestAccess.clearAuthentication())
                        .header("X-Webhook-Token", "valid-token")
                        .header("Idempotency-Key", "order-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21));
    }

    @Test
    void webhookWithInvalidTokenIsRejectedWithoutJwt() throws Exception {
        mockMvc.perform(post("/api/webhooks")
                        .with(TestAccess.clearAuthentication())
                        .header("Idempotency-Key", "order-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(webhooks);
    }

    @Test
    void unspecifiedPathIsDenied() throws Exception {
        mockMvc.perform(get("/not-an-api").with(TestAccess.clearAuthentication()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/not-an-api").with(TestAccess.user()))
                .andExpect(status().isForbidden());
    }
}
