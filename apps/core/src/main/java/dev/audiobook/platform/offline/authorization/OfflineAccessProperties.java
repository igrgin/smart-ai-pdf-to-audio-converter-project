package dev.audiobook.platform.offline.authorization;

import dev.audiobook.platform.offline.authorization.service.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("platform.offline-access")
public record OfflineAccessProperties(
        Duration authorizationValidity,
        int chunkBytes,
        String signingPrivateKey,
        String signingPublicKey,
        String signingKeyVersion) {

    public OfflineAccessProperties {
        if (authorizationValidity == null
                || authorizationValidity.isZero()
                || authorizationValidity.isNegative()
                || authorizationValidity.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "Offline authorization validity must be between zero and 30 days");
        }
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("Offline chunk bytes must be positive");
        }
    }
}
