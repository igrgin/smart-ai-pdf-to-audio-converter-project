package dev.audiobook.platform.entitlement.internal.subscription.stripe;

public interface StripeWebhookVerifier {

    VerifiedStripeEvent verify(String payload, String signatureHeader);
}
