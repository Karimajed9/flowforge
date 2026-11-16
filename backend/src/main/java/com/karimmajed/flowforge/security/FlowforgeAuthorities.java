package com.karimmajed.flowforge.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class FlowforgeAuthorities {

    private static final JwtGrantedAuthoritiesConverter SCOPES = new JwtGrantedAuthoritiesConverter();

    private FlowforgeAuthorities() {
    }

    static Collection<GrantedAuthority> from(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        Collection<GrantedAuthority> fromScopes = SCOPES.convert(jwt);
        if (fromScopes != null) {
            authorities.addAll(fromScopes);
        }
        addRealmRole(authorities, jwt, "flowforge-user", SecurityConfig.USER);
        addRealmRole(authorities, jwt, "flowforge-admin", SecurityConfig.ADMIN);
        if (authorities.stream().anyMatch(authority ->
                SecurityConfig.ADMIN.equals(authority.getAuthority()))) {
            authorities.add(new SimpleGrantedAuthority(SecurityConfig.USER));
        }
        return authorities;
    }

    private static void addRealmRole(
            Set<GrantedAuthority> authorities,
            Jwt jwt,
            String role,
            String authority) {

        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access)) {
            return;
        }
        Object roles = access.get("roles");
        if (!(roles instanceof Collection<?> values)) {
            return;
        }
        if (values.contains(role)) {
            authorities.add(new SimpleGrantedAuthority(authority));
        }
    }
}
