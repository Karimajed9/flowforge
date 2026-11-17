package com.karimmajed.flowforge.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    static final String USER = "SCOPE_flowforge.user";
    static final String ADMIN = "SCOPE_flowforge.admin";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {

        // Stateless bearer tokens. CSRF applies to cookie sessions, which this API does not use.
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/webhooks").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority(ADMIN)
                        .requestMatchers("/api/**").hasAuthority(USER)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(FlowforgeAuthorities::from);
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${flowforge.security.issuer-uri}") String issuer,
            @Value("${flowforge.security.audience}") String audience,
            @Value("${flowforge.security.public-key-location:}") String publicKeyLocation) {

        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalStateException("OIDC issuer and audience are required");
        }
        // Discovery contacts the issuer. Doing that here stops the process when
        // Keycloak is not up yet. The first bearer token still resolves it, and
        // a missing issuer or audience still fails startup above.
        return new DeferredJwtDecoder(() -> {
            try {
                return buildDecoder(issuer, audience, publicKeyLocation);
            } catch (IOException ex) {
                throw new IllegalStateException("OIDC public key could not be read", ex);
            }
        });
    }

    private static NimbusJwtDecoder buildDecoder(
            String issuer,
            String audience,
            String publicKeyLocation) throws IOException {

        NimbusJwtDecoder decoder = publicKeyLocation == null || publicKeyLocation.isBlank()
                ? (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer)
                : NimbusJwtDecoder.withPublicKey(readPublicKey(publicKeyLocation)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                audienceValidator(audience)));
        return decoder;
    }

    private static final class DeferredJwtDecoder implements JwtDecoder {

        private final DecoderFactory factory;
        private volatile JwtDecoder delegate;

        private DeferredJwtDecoder(DecoderFactory factory) {
            this.factory = factory;
        }

        @Override
        public Jwt decode(String token) {
            try {
                return decoder().decode(token);
            } catch (JwtException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new BadJwtException("The token issuer could not be reached", ex);
            }
        }

        private JwtDecoder decoder() {
            JwtDecoder current = delegate;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (delegate == null) {
                    delegate = factory.create();
                }
                return delegate;
            }
        }

        @FunctionalInterface
        private interface DecoderFactory {
            JwtDecoder create();
        }
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return token -> {
            if (token.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The token audience is not " + audience,
                    null));
        };
    }

    private static RSAPublicKey readPublicKey(String location) throws IOException {
        Resource resource = location.startsWith("classpath:")
                ? new ClassPathResource(location.substring("classpath:".length()))
                : new FileSystemResource(location);
        try (InputStream input = resource.getInputStream()) {
            RSAPublicKey key = RsaKeyConverters.x509().convert(input);
            if (key == null) {
                throw new IllegalStateException("OIDC public key could not be read");
            }
            return key;
        }
    }
}
