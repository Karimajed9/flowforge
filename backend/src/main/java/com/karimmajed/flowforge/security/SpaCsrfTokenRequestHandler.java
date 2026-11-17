package com.karimmajed.flowforge.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * The SPA and the logout form both submit the raw token from
 * GET /api/auth/csrf. The header and the form field use that same value.
 * XOR masking would make the form field fail, so logout could not be a
 * top-level navigation to Keycloak.
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken) {

        super.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        return super.resolveCsrfTokenValue(request, csrfToken);
    }
}
