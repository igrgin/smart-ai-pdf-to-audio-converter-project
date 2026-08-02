package dev.audiobook.platform.entitlement.subscription.stripe;

import dev.audiobook.platform.entitlement.subscription.stripe.service.*;

public final class StripeWebhookVerificationException extends RuntimeException {

    public StripeWebhookVerificationException(String message) {
        super(message);
    }

    public StripeWebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
