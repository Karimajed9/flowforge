package com.karimmajed.flowforge.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Matches a bearer token only when the browser did not send the session
 * cookie. A page that already has a session cannot attach a foreign bearer
 * and land on the chain that skips CSRF.
 */
final class BearerAuthorizationMatcher implements RequestMatcher {

    static final String SESSION_COOKIE = "SESSION";

    @Override
    public boolean matches(HttpServletRequest request) {
        if (hasSessionCookie(request)) {
            return false;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static boolean hasSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
