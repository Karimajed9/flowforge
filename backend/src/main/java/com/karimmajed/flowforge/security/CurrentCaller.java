package com.karimmajed.flowforge.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URL;

@Component
public class CurrentCaller {

    public ResourceOwner require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw unauthorized();
        }

        String subject = jwt.getSubject();
        URL issuer = jwt.getIssuer();
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
