package dev.audiobook.platform.entitlement.subscription.stripe;

import com.fasterxml.jackson.databind.JsonNode;

import dev.audiobook.platform.entitlement.subscription.stripe.service.*;

import java.time.Instant;

public record VerifiedStripeEvent(
        String eventId,
        String eventType,
        Instant eventCreated,
        String payload,
        String payloadSha256,
        JsonNode event) {}
