package dev.audiobook.platform.entitlement.subscription.stripe.service;

import dev.audiobook.platform.entitlement.subscription.stripe.*;

public interface StripeEventInboxService {

    Receipt accept(VerifiedStripeEvent event);

    record Receipt(String eventId, boolean received) {}
}
