package dev.audiobook.platform.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

final class BrokerAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    BrokerAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository);
        delegate.setAuthorizationRequestCustomizer(builder -> builder.additionalParameters(parameters -> {
            parameters.put("prompt", "login");
            parameters.put("max_age", "0");
        }));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return delegate.resolve(request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return delegate.resolve(request, clientRegistrationId);
    }
}
