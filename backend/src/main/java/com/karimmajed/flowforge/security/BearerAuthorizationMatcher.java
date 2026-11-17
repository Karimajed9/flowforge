package com.karimmajed.flowforge.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches a caller that presented an Authorization bearer token.
 * The scheme comparison is case-insensitive. A cookie alone does not match.
 */
final class BearerAuthorizationMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
