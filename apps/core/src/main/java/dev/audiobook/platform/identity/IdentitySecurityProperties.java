package dev.audiobook.platform.identity;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.identity")
public record IdentitySecurityProperties(
        URI allowedOrigin,
        URI brokerIssuer,
        URI recoveryUri,
        Map<SignInProvider, String> brokerProviderIds,
        boolean secureSessionCookie,
        Duration freshAuthenticationMaxAge,
        Duration sessionAbsoluteTimeout,
        Duration sessionRotationInterval) {

    public IdentitySecurityProperties {
        brokerProviderIds = Map.copyOf(brokerProviderIds);
    }

    String brokerProviderId(SignInProvider provider) {
        String providerId = brokerProviderIds.get(provider);
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException("Missing ZITADEL broker provider ID for " + provider.name());
        }
        return providerId;
    }
}
