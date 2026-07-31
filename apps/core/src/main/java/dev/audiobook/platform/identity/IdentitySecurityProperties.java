package dev.audiobook.platform.identity;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.identity")
public record IdentitySecurityProperties(
        URI allowedOrigin,
        URI brokerIssuer,
        URI recoveryUri,
        Duration freshAuthenticationMaxAge,
        Duration sessionAbsoluteTimeout,
        Duration sessionRotationInterval) {
}
