package dev.audiobook.platform.entitlement.internal.stripe;

public interface StripeWebhookVerifier {

    VerifiedStripeEvent verify(String payload, String signatureHeader);
}
