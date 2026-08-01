package dev.audiobook.platform.entitlement;

public interface StripeEventInboxService {

    Receipt accept(VerifiedStripeEvent event);

    record Receipt(String eventId, boolean received) {
    }
}
