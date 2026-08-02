package dev.audiobook.platform.entitlement.internal.ledger;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.entitlement")
public record EntitlementPolicyProperties(
        long freeGrantCharacters,
        Duration freeGrantValidity,
        long perConversionCharacterCeiling,
        long perConversionSpendCeilingMicros,
        long perListenerSpendCeilingMicros,
        long providerSpendCeilingMicros,
        long globalSpendCeilingMicros,
        int perListenerConcurrency,
        int globalConcurrency) {

    public EntitlementPolicyProperties {
        if (freeGrantCharacters <= 0) {
            throw new IllegalArgumentException("Free grant characters must be positive");
        }
        if (freeGrantValidity == null || freeGrantValidity.isNegative() || freeGrantValidity.isZero()) {
            throw new IllegalArgumentException("Free grant validity must be positive");
        }
        if (perConversionCharacterCeiling <= 0
                || perConversionSpendCeilingMicros <= 0
                || perListenerSpendCeilingMicros <= 0
                || providerSpendCeilingMicros <= 0
                || globalSpendCeilingMicros <= 0
                || perListenerConcurrency <= 0
                || globalConcurrency <= 0) {
            throw new IllegalArgumentException("Entitlement admission ceilings must be positive");
        }
    }
}
