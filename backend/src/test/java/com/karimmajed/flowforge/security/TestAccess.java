package com.karimmajed.flowforge.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestAccess {

    public static final String ISSUER = "http://auth.test/realms/flowforge";
    public static final String AUDIENCE = "flowforge-api";

    private TestAccess() {
    }

    public static RequestPostProcessor user() {
        return jwt().jwt(token -> token.claim("scope", "flowforge.user"));
    }

    public static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.claim("scope", "flowforge.admin"));
    }

    public static RequestPostProcessor openIdOnly() {
        return jwt().jwt(token -> token.claim("scope", "openid"));
    }

    public static RequestPostProcessor realmUser() {
        return jwt()
                .jwt(token -> token
                        .claim("scope", "openid")
                        .claim("realm_access", Map.of("roles", List.of("flowforge-user"))))
                .authorities(FlowforgeAuthorities::from);
    }

    public static RequestPostProcessor clearAuthentication() {
        return request -> {
            TestSecurityContextHolder.clearContext();
            return request;
        };
    }

    public static String signedToken(String issuer, String audience, String scope) {
        try {
            RSAPrivateKey key = privateKey();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("scope", scope)
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign a test token", ex);
        }
    }

    private static RSAPrivateKey privateKey() throws Exception {
        try (InputStream input = new ClassPathResource("security/test-jwt-private.pem").getInputStream()) {
            RSAPrivateKey key = (RSAPrivateKey) RsaKeyConverters.pkcs8().convert(input);
            if (key == null) {
                throw new IllegalStateException("Test private key could not be read");
            }
            return key;
        }
    }
}
