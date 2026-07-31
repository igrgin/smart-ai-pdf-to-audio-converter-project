package dev.audiobook.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

final class BrokerAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final IdentitySecurityProperties properties;

    BrokerAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            IdentitySecurityProperties properties) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
        this.properties = properties;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        OAuth2AuthorizationRequest resolved = delegate.resolve(request);
        return resolved == null ? null : harden(resolved, resolved.getAttribute("registration_id"));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        OAuth2AuthorizationRequest resolved = delegate.resolve(request, clientRegistrationId);
        return resolved == null ? null : harden(resolved, clientRegistrationId);
    }

    private OAuth2AuthorizationRequest harden(
            OAuth2AuthorizationRequest authorizationRequest,
            String clientRegistrationId) {
        SignInProvider provider = SignInProvider.fromRegistrationId(clientRegistrationId);
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(parameters -> {
                    parameters.put("prompt", "login");
                    parameters.put("max_age", "0");
                    parameters.put("idp", properties.brokerProviderId(provider));
                })
                .build();
    }
}
