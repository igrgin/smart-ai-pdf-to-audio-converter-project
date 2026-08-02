package dev.audiobook.platform.entitlement.internal.stripe;

public interface StripeEventInboxService {

    Receipt accept(VerifiedStripeEvent event);

    record Receipt(String eventId, boolean received) {
    }
}
