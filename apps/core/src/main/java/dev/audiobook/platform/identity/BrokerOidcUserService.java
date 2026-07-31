package dev.audiobook.platform.identity;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

final class BrokerOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService delegate = new OidcUserService();
    private final BrokerIdentity brokerIdentity;

    BrokerOidcUserService(BrokerIdentity brokerIdentity) {
        this.brokerIdentity = brokerIdentity;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser user = delegate.loadUser(userRequest);
        SignInProvider provider = SignInProvider.fromRegistrationId(
                userRequest.getClientRegistration().getRegistrationId());
        brokerIdentity.from(provider, user);
        return user;
    }
}
