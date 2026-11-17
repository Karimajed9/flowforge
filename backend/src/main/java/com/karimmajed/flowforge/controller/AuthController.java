package com.karimmajed.flowforge.controller;

import com.karimmajed.flowforge.dto.AuthProfile;
import com.karimmajed.flowforge.security.SecurityConfig;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthController {

    private final CsrfTokenRepository csrfTokens;

    public AuthController(CsrfTokenRepository csrfTokens) {
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/login")
    public void login(HttpServletResponse response) throws IOException {
        response.sendRedirect("/oauth2/authorization/" + SecurityConfig.OIDC_REGISTRATION_ID);
    }

    @GetMapping("/me")
    public AuthProfile me(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        String subject = null;
        String issuer = null;
        String username = null;
        if (principal instanceof Jwt jwt) {
            subject = jwt.getSubject();
            URL iss = jwt.getIssuer();
            issuer = iss == null ? null : iss.toString();
            username = textClaim(jwt.getClaim("preferred_username"));
        } else if (principal instanceof OidcUser oidc) {
            subject = oidc.getSubject();
            URL iss = oidc.getIssuer();
            issuer = iss == null ? null : iss.toString();
            username = textClaim(oidc.getClaim("preferred_username"));
        }
        if (username == null) {
            username = subject;
        }
        return new AuthProfile(subject, issuer, username, permissions(authentication));
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = this.csrfTokens.loadToken(request);
        if (token == null) {
            token = this.csrfTokens.generateToken(request);
            this.csrfTokens.saveToken(token, request, response);
        }
        return Map.of(
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName(),
                "token", token.getToken());
    }

    private static List<String> permissions(Authentication authentication) {
        List<String> permissions = new ArrayList<>();
        if (has(authentication, SecurityConfig.USER)) {
            permissions.add("flowforge.user");
        }
        if (has(authentication, SecurityConfig.ADMIN)) {
            permissions.add("flowforge.admin");
        }
        return List.copyOf(permissions);
    }

    private static boolean has(Authentication authentication, String authority) {
        for (GrantedAuthority granted : authentication.getAuthorities()) {
            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String textClaim(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
