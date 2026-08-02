package dev.audiobook.platform.entitlement.subscription.stripe;

import dev.audiobook.platform.entitlement.subscription.stripe.service.*;

public interface StripeWebhookVerifier {

    VerifiedStripeEvent verify(String payload, String signatureHeader);
}
