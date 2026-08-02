package dev.audiobook.platform.entitlement.subscription;

import dev.audiobook.platform.entitlement.subscription.service.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("platform.demonstration-subscription")
public record DemonstrationSubscriptionProperties(
        String webhookSecret,
        String monthlyPriceId,
        long monthlyGrantCharacters,
        Duration signatureTolerance,
        boolean projectorEnabled) {

    public DemonstrationSubscriptionProperties {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("Stripe webhook secret must be configured");
        }
        if (monthlyPriceId == null || monthlyPriceId.isBlank()) {
            throw new IllegalArgumentException("Demonstration monthly price ID must be configured");
        }
        if (monthlyGrantCharacters <= 0) {
            throw new IllegalArgumentException("Demonstration monthly grant must be positive");
        }
        if (signatureTolerance == null
                || signatureTolerance.isNegative()
                || signatureTolerance.isZero()) {
            throw new IllegalArgumentException("Stripe signature tolerance must be positive");
        }
    }
}
