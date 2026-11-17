package com.karimmajed.flowforge.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URL;

@Component
public class CurrentCaller {

    public ResourceOwner require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw unauthorized();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return owner(jwt.getSubject(), jwt.getIssuer());
        }
        if (principal instanceof OidcUser oidc) {
            return owner(oidc.getSubject(), oidc.getIssuer());
        }
        throw unauthorized();
    }

    private static ResourceOwner owner(String subject, URL issuer) {
        if (subject == null || subject.isBlank() || issuer == null || issuer.toString().isBlank()) {
            throw unauthorized();
        }
        return new ResourceOwner(subject, issuer.toString());
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication is required");
    }
}
