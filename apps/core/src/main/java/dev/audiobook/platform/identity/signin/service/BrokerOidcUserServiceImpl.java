package dev.audiobook.platform.identity.signin.service;

import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.signin.*;

import lombok.RequiredArgsConstructor;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@RequiredArgsConstructor
public final class BrokerOidcUserServiceImpl implements BrokerOidcUserService {

    private final OidcUserService delegate = new OidcUserService();
    private final BrokerIdentity brokerIdentity;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser user = delegate.loadUser(userRequest);
        SignInProvider provider =
                SignInProvider.fromRegistrationId(
                        userRequest.getClientRegistration().getRegistrationId());
        brokerIdentity.from(provider, user);
        return user;
    }
}
