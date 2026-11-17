package com.karimmajed.flowforge.security;

import com.karimmajed.flowforge.controller.AuthController;
import com.karimmajed.flowforge.controller.ExecutionRecoveryController;
import com.karimmajed.flowforge.controller.WebhookController;
import com.karimmajed.flowforge.controller.WorkflowController;
import com.karimmajed.flowforge.dto.WebhookTriggerResult;
import com.karimmajed.flowforge.dto.WorkflowExecutionResponse;
import com.karimmajed.flowforge.dto.WorkflowResponse;
import com.karimmajed.flowforge.enums.ExecutionStatus;
import com.karimmajed.flowforge.service.ExecutionAbandonmentService;
import com.karimmajed.flowforge.service.ExecutionRecoveryService;
import com.karimmajed.flowforge.service.WebhookService;
import com.karimmajed.flowforge.service.WorkflowOwnership;
import com.karimmajed.flowforge.service.WorkflowService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {
                AuthController.class,
                WorkflowController.class,
                WebhookController.class,
                ExecutionRecoveryController.class
        },
        properties = {
                "flowforge.recovery.enabled=true",
                "flowforge.recovery.token=test-recovery-key"
        }
)
class BffAuthenticationTest {

    private static final String ID_TOKEN = "must-not-leak-id-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CookieSerializer sessionCookies;

    @MockitoBean
    private WorkflowService workflows;

    @MockitoBean
    private WebhookService webhooks;

    @MockitoBean
    private ExecutionRecoveryService recovery;

    @MockitoBean
    private ExecutionAbandonmentService abandonment;

    @MockitoBean
    private WorkflowOwnership ownership;

    @Test
    void unauthenticatedApiFetchIsJsonAndDoesNotRedirect() throws Exception {
        mockMvc.perform(get("/api/workflows").with(TestAccess.clearAuthentication()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(JsonAuthenticationEntryPoint.BODY))
                .andExpect(header().doesNotExist("Location"));

        mockMvc.perform(get("/api/auth/me").with(TestAccess.clearAuthentication()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(JsonAuthenticationEntryPoint.BODY));
    }

    @Test
    void loginRedirectsToKeycloakWithoutReturningTokens() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/login").with(TestAccess.clearAuthentication()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/oauth2/authorization/keycloak"))
                .andReturn();

        assertNoOAuthTokens(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("access_token");
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("client_secret");
    }

    @Test
    void authorizationRequestUsesPkceAndKeepsTheSecretOnTheServer() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/keycloak")
                        .with(TestAccess.clearAuthentication()))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).contains("/protocol/openid-connect/auth");
        assertThat(location).contains("response_type=code");
        assertThat(location).contains("code_challenge=");
        assertThat(location).contains("code_challenge_method=S256");
        assertThat(location).doesNotContain("client_secret");
        assertThat(location).doesNotContain("test-client-secret");
        assertThat(location).doesNotContain("access_token");
    }

    @Test
    void profileAndCsrfResponsesDoNotContainOAuthTokens() throws Exception {
        MvcResult csrf = mockMvc.perform(get("/api/auth/csrf").with(TestAccess.clearAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        assertNoOAuthTokens(csrf.getResponse().getContentAsString());
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Lax");

        MvcResult profile = mockMvc.perform(get("/api/auth/me")
                        .with(sessionUser("session-user", "Ada", SecurityConfig.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("session-user"))
                .andExpect(jsonPath("$.issuer").value(TestAccess.ISSUER))
                .andExpect(jsonPath("$.username").value("Ada"))
                .andExpect(jsonPath("$.permissions[0]").value("flowforge.user"))
                .andReturn();
        assertNoOAuthTokens(profile.getResponse().getContentAsString());
        assertThat(profile.getResponse().getContentAsString()).doesNotContain(ID_TOKEN);
    }

    @Test
    void csrfCookieIsSecureOnlyWhenTheRequestIsHttps() throws Exception {
        MvcResult http = mockMvc.perform(get("/api/auth/csrf").with(TestAccess.clearAuthentication()))
                .andReturn();
        Cookie plain = http.getResponse().getCookie("XSRF-TOKEN");
        assertThat(plain).isNotNull();
        assertThat(plain.getSecure()).isFalse();
        assertThat(plain.getAttribute("SameSite")).isEqualTo("Lax");

        MvcResult https = mockMvc.perform(get("/api/auth/csrf")
                        .secure(true)
                        .with(TestAccess.clearAuthentication()))
                .andReturn();
        Cookie secure = https.getResponse().getCookie("XSRF-TOKEN");
        assertThat(secure).isNotNull();
        assertThat(secure.getSecure()).isTrue();
        assertThat(secure.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void sessionCookieIsOpaqueHttpOnlyAndSecureOnlyOnHttps() {
        MockHttpServletResponse http = writeSessionCookie(false);
        String plain = http.getHeader("Set-Cookie");
        assertThat(plain).startsWith("SESSION=opaque-session-id");
        assertThat(plain).contains("HttpOnly");
        assertThat(plain).contains("SameSite=Lax");
        assertThat(plain.toLowerCase()).doesNotContain("secure");
        assertThat(plain).doesNotContain("access_token");
        assertThat(plain).doesNotContain(ID_TOKEN);

        String secure = writeSessionCookie(true).getHeader("Set-Cookie");
        assertThat(secure).contains("HttpOnly");
        assertThat(secure).contains("Secure");
        assertThat(secure).contains("SameSite=Lax");
    }

    @Test
    void cookieSessionRequiresCsrfAndUsesTheOidcIdentity() throws Exception {
        when(workflows.create(any(), any())).thenReturn(workflow());

        mockMvc.perform(post("/api/workflows")
                        .with(sessionUser("session-user", "Ada", SecurityConfig.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Owned\",\"ownerSubject\":\"someone-else\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(workflows);

        GrantedCsrf csrf = grantedCsrf();
        mockMvc.perform(post("/api/workflows")
                        .with(sessionUser("session-user", "Ada", SecurityConfig.USER))
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Owned\",\"ownerSubject\":\"someone-else\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4));

        verify(workflows).create(any(), eq(new ResourceOwner("session-user", TestAccess.ISSUER)));
    }

    @Test
    void bearerCallStillRequiresTheTokenIdentityAndNotCsrf() throws Exception {
        when(workflows.create(any(), any())).thenReturn(workflow());
        String token = TestAccess.signedToken(
                TestAccess.ISSUER,
                TestAccess.AUDIENCE,
                "flowforge.user");

        MvcResult result = mockMvc.perform(post("/api/workflows")
                        .with(TestAccess.clearAuthentication())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Owned\",\"ownerSubject\":\"someone-else\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        assertNoOAuthTokens(result.getResponse().getContentAsString());
        verify(workflows).create(any(), eq(new ResourceOwner("user", TestAccess.ISSUER)));
    }

    @Test
    void webhookDoesNotNeedASessionOrCsrf() throws Exception {
        when(webhooks.trigger(eq("valid-token"), eq("order-123"), any()))
                .thenReturn(new WebhookTriggerResult(
                        new WorkflowExecutionResponse(
                                21L,
                                7L,
                                ExecutionStatus.RECEIVED,
                                OffsetDateTime.parse("2026-11-16T15:04:00Z")),
                        false));

        MvcResult result = mockMvc.perform(post("/api/webhooks")
                        .with(TestAccess.clearAuthentication())
                        .header("X-Webhook-Token", "valid-token")
                        .header("Idempotency-Key", "order-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":150}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(21))
                .andReturn();

        assertNoOAuthTokens(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("SESSION="));
        verify(webhooks).trigger(eq("valid-token"), eq("order-123"), any());
    }

    @Test
    void sessionRecoveryStillNeedsAdminKeyAndOwnership() throws Exception {
        GrantedCsrf csrf = grantedCsrf();
        RequestPostProcessor admin = sessionUser(
                "admin-owner",
                "admin-owner",
                SecurityConfig.ADMIN);

        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(admin)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(recovery, ownership);

        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found"))
                .when(ownership).requireExecution(eq(21L), eq(new ResourceOwner("admin-owner", TestAccess.ISSUER)));

        mockMvc.perform(post("/api/admin/executions/{id}/recover", 21)
                        .with(admin)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .header("X-Recovery-Key", "test-recovery-key"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(recovery);

        mockMvc.perform(post("/api/admin/executions/{id}/steps/{stepId}/abandon", 21, 8)
                        .with(admin)
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptNumber\":1,\"reason\":\"stuck\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(abandonment);
    }

    @Test
    void logoutEndsTheKeycloakSessionWithoutReturningTokens() throws Exception {
        GrantedCsrf csrf = grantedCsrf();
        MvcResult result = mockMvc.perform(post("/api/auth/logout")
                        .with(sessionUser("session-user", "Ada", SecurityConfig.USER))
                        .cookie(csrf.cookie())
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).contains("/protocol/openid-connect/logout");
        assertThat(location).doesNotContain("access_token");
        assertThat(location).doesNotContain("refresh_token");
        assertThat(location).doesNotContain("client_secret");
        assertNoOAuthTokens(result.getResponse().getContentAsString());
    }

    @Test
    void logoutWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .with(sessionUser("session-user", "Ada", SecurityConfig.USER)))
                .andExpect(status().isForbidden());
    }

    private GrantedCsrf grantedCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").with(TestAccess.clearAuthentication()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");
        String headerName = com.jayway.jsonpath.JsonPath.read(body, "$.headerName");
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return new GrantedCsrf(headerName, token, cookie);
    }

    private static RequestPostProcessor sessionUser(String subject, String username, String... authorities) {
        OidcIdToken idToken = new OidcIdToken(
                ID_TOKEN,
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of(
                        "sub", subject,
                        "iss", TestAccess.ISSUER,
                        "preferred_username", username));
        List<SimpleGrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        DefaultOidcUser user = new DefaultOidcUser(granted, idToken, "sub");
        return authentication(new OAuth2AuthenticationToken(
                user,
                granted,
                SecurityConfig.OIDC_REGISTRATION_ID));
    }

    private MockHttpServletResponse writeSessionCookie(boolean secure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setSecure(secure);
        MockHttpServletResponse response = new MockHttpServletResponse();
        sessionCookies.writeCookieValue(new CookieSerializer.CookieValue(
                request,
                response,
                "opaque-session-id"));
        return response;
    }

    private static void assertNoOAuthTokens(String body) {
        assertThat(body).doesNotContain("access_token");
        assertThat(body).doesNotContain("refresh_token");
        assertThat(body).doesNotContain("id_token");
        assertThat(body).doesNotContain("client_secret");
        assertThat(body).doesNotContain("test-client-secret");
    }

    private static WorkflowResponse workflow() {
        return new WorkflowResponse(
                4L,
                "Owned",
                null,
                true,
                OffsetDateTime.parse("2026-11-16T15:04:00Z"));
    }

    private record GrantedCsrf(String headerName, String token, Cookie cookie) {
    }
}
