package dev.audiobook.platform.entitlement.internal.subscription.stripe;

public final class StripeWebhookVerificationException extends RuntimeException {

    public StripeWebhookVerificationException(String message) {
        super(message);
    }

    public StripeWebhookVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
