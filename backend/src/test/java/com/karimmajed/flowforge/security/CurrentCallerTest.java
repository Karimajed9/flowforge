package com.karimmajed.flowforge.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentCallerTest {

    private final CurrentCaller caller = new CurrentCaller();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bearerIdentitySuppliesSubjectAndIssuer() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-a")
                .issuer(TestAccess.ISSUER)
                .build();
        authenticate(new JwtAuthenticationToken(jwt));

        assertThat(caller.require()).isEqualTo(new ResourceOwner("user-a", TestAccess.ISSUER));
    }

    @Test
    void oidcIdentityUsesTheIdTokenNotTheDisplayName() {
        OidcIdToken idToken = new OidcIdToken(
                "must-not-leak-id-token",
                Instant.now(),
                Instant.now().plusSeconds(60),
                Map.of(
                        "sub", "user-a",
                        "iss", TestAccess.ISSUER,
                        "preferred_username", "Ada"));
        DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority(SecurityConfig.USER)),
                idToken,
                "sub");
        authenticate(new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                user,
                user.getAuthorities(),
                SecurityConfig.OIDC_REGISTRATION_ID));

        assertThat(caller.require()).isEqualTo(new ResourceOwner("user-a", TestAccess.ISSUER));
    }

    @Test
    void blankOrMissingIdentityIsUnauthorized() {
        assertThatThrownBy(caller::require)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        authenticate(new UsernamePasswordAuthenticationToken("browser-role", "nope"));
        assertThatThrownBy(caller::require)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(" ")
                .issuer(TestAccess.ISSUER)
                .build();
        authenticate(new JwtAuthenticationToken(jwt));
        assertThatThrownBy(caller::require)
                .isInstanceOf(ResponseStatusException.class);
    }

    private static void authenticate(org.springframework.security.core.Authentication authentication) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
