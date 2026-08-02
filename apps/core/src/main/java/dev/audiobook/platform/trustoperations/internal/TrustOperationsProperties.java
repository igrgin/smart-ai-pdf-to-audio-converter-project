package dev.audiobook.platform.trustoperations.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.trust-operations")
public record TrustOperationsProperties(
        Duration delegatedAccessMaximumDuration,
        Duration emergencyAccessMaximumDuration,
        Duration emergencyReviewDeadline,
        Duration freshMfaMaximumAge) {

    public TrustOperationsProperties {
        if (delegatedAccessMaximumDuration == null || delegatedAccessMaximumDuration.isNegative()
                || delegatedAccessMaximumDuration.isZero()) {
            throw new IllegalArgumentException("Delegated access maximum duration must be positive");
        }
        if (emergencyAccessMaximumDuration == null || emergencyAccessMaximumDuration.isNegative()
                || emergencyAccessMaximumDuration.isZero()) {
            throw new IllegalArgumentException("Emergency access maximum duration must be positive");
        }
        if (emergencyReviewDeadline == null || emergencyReviewDeadline.isNegative()
                || emergencyReviewDeadline.isZero()) {
            throw new IllegalArgumentException("Emergency review deadline must be positive");
        }
        if (freshMfaMaximumAge == null || freshMfaMaximumAge.isNegative() || freshMfaMaximumAge.isZero()) {
            throw new IllegalArgumentException("Fresh MFA maximum age must be positive");
        }
    }
}
