package dev.audiobook.platform.entitlement;

public interface StripeWebhookVerifier {

    VerifiedStripeEvent verify(String payload, String signatureHeader);
}
