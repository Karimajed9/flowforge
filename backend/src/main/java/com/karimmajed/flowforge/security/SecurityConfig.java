package com.karimmajed.flowforge.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * Two browser-facing modes stay active during the move to a BFF.
 *
 * A request with Authorization: Bearer uses the stateless resource server.
 * That is the mode the current React login still uses. Ownership and role
 * checks still run. CSRF is not applied on that chain because a browser
 * does not attach the Authorization header by itself, so a third-party site
 * cannot ride a bearer call the way it can ride a cookie.
 *
 * Every other request uses the session chain. The browser holds only an
 * opaque session cookie. Access, refresh, and ID tokens stay in that
 * session. Unsafe cookie requests require a CSRF token.
 *
 * POST /api/webhooks is a third chain. It accepts the webhook token and
 * neither a browser session nor a CSRF token.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    public static final String OIDC_REGISTRATION_ID = "keycloak";

    public static final String USER = "SCOPE_flowforge.user";
    public static final String ADMIN = "SCOPE_flowforge.admin";

    @Bean
    @Order(0)
    SecurityFilterChain webhookSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatchers(matchers -> matchers.requestMatchers(HttpMethod.POST, "/api/webhooks"))
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain bearerSecurityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

        http.securityMatcher(new BearerAuthorizationMatcher())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> apiAuthorization(auth))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new JsonAuthenticationEntryPoint()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain sessionSecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clients,
            OAuth2AuthorizedClientRepository authorizedClients,
            FlowforgeOidcUserService oidcUsers,
            CsrfTokenRepository csrfTokens) throws Exception {

        http.securityMatcher(request -> true)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        .ignoringRequestMatchers(jwtPrincipalOnSessionChain()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/login", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").permitAll()
                        .requestMatchers("/oauth2/authorization/**", "/login/oauth2/code/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority(ADMIN)
                        .requestMatchers("/api/**").hasAuthority(USER)
                        .anyRequest().denyAll())
                .oauth2Login(oauth -> oauth
                        .clientRegistrationRepository(clients)
                        .authorizedClientRepository(authorizedClients)
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUsers)))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(oidcLogout(clients)))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new JsonAuthenticationEntryPoint()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * The session chain does not decode bearer tokens. A Jwt principal only
     * appears here in tests that install one without an Authorization header.
     * Production bearer calls match the stateless chain and never reach this
     * ignore. Cookie sessions (an OIDC user) still require CSRF.
     */
    private static RequestMatcher jwtPrincipalOnSessionChain() {
        return request -> jwtPrincipal(SecurityContextHolder.getContext().getAuthentication())
                || jwtPrincipal(sessionContext(request));
    }

    private static Authentication sessionContext(HttpServletRequest request) {
        Object attribute = request.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (attribute instanceof SecurityContext context) {
            return context.getAuthentication();
        }
        return null;
    }

    private static boolean jwtPrincipal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof Jwt;
    }

    private static void apiAuthorization(
            org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {

        auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority(ADMIN)
                .requestMatchers("/api/**").hasAuthority(USER)
                .anyRequest().denyAll();
    }

    private static LogoutSuccessHandler oidcLogout(ClientRegistrationRepository clients) {
        OidcClientInitiatedLogoutSuccessHandler logout =
                new OidcClientInitiatedLogoutSuccessHandler(clients);
        logout.setPostLogoutRedirectUri("{baseUrl}");
        return logout;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        return repository;
    }

    /**
     * Opaque session id. HttpOnly so script cannot read it. SameSite=Lax so
     * a cross-site form post does not include it, while same-site navigation
     * and the Vite dev proxy (the browser talks to the dev server, which
     * forwards /api) still do. Secure is left unset: the cookie is marked
     * Secure only when the request itself is HTTPS.
     */
    @Bean
    CookieSerializer sessionCookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseBase64Encoding(false);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(
            @Value("${flowforge.security.issuer-uri}") String issuer,
            @Value("${flowforge.security.client-id:flowforge-bff}") String clientId,
            @Value("${flowforge.security.client-secret:}") String clientSecret) {

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("OIDC issuer and audience are required");
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        ClientRegistration registration = ClientRegistration.withRegistrationId(OIDC_REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret == null ? "" : clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile")
                .authorizationUri(base + "/protocol/openid-connect/auth")
                .tokenUri(base + "/protocol/openid-connect/token")
                .jwkSetUri(base + "/protocol/openid-connect/certs")
                .userInfoUri(base + "/protocol/openid-connect/userinfo")
                .issuerUri(base)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientSettings(ClientRegistration.ClientSettings.builder()
                        .requireProofKey(true)
                        .build())
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint",
                        base + "/protocol/openid-connect/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    FlowforgeOidcUserService flowforgeOidcUserService(JwtDecoder jwtDecoder) {
        return new FlowforgeOidcUserService(jwtDecoder);
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
