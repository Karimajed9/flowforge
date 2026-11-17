package com.karimmajed.flowforge.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Collection;

/**
 * Roles come from the access token after this server has checked its
 * signature, issuer, and audience. The browser does not send them.
 */
final class FlowforgeOidcUserService extends OidcUserService {

    private final JwtDecoder jwtDecoder;

    FlowforgeOidcUserService(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser loaded = super.loadUser(userRequest);
        Jwt accessToken = this.jwtDecoder.decode(userRequest.getAccessToken().getTokenValue());
        Collection<GrantedAuthority> authorities = FlowforgeAuthorities.from(accessToken);
        OidcIdToken idToken = loaded.getIdToken();
        String nameAttribute = idToken.hasClaim(StandardClaimNames.SUB)
                ? StandardClaimNames.SUB
                : userRequest.getClientRegistration()
                        .getProviderDetails()
                        .getUserInfoEndpoint()
                        .getUserNameAttributeName();
        OidcUserInfo userInfo = loaded.getUserInfo();
        if (userInfo == null) {
            return new DefaultOidcUser(authorities, idToken, nameAttribute);
        }
        return new DefaultOidcUser(authorities, idToken, userInfo, nameAttribute);
    }
}
