package com.karimmajed.flowforge.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import java.util.function.Supplier;

/**
 * Form posts keep the XOR masking that limits BREACH. A JavaScript client
 * sends the raw token in the CSRF header, which is the value returned by
 * GET /api/auth/csrf and stored in the readable CSRF cookie.
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            Supplier<CsrfToken> csrfToken) {

        this.masked.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        if (request.getHeader(csrfToken.getHeaderName()) != null) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
        return this.masked.resolveCsrfTokenValue(request, csrfToken);
    }
}
