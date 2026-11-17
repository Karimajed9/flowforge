package com.karimmajed.flowforge.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Unauthenticated calls get a JSON 401. They are not redirected to Keycloak.
 * Login is a separate permitAll entry that performs the redirect itself.
 */
final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String BODY = "{\"status\":401,\"error\":\"Unauthorized\"}";

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(BODY);
    }
}
