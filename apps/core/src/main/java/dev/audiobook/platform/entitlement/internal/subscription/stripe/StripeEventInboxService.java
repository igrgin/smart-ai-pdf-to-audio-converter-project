package dev.audiobook.platform.entitlement.internal.subscription.stripe;

public interface StripeEventInboxService {

    Receipt accept(VerifiedStripeEvent event);

    record Receipt(String eventId, boolean received) {
    }
}
