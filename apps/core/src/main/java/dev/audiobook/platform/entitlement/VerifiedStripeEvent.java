package dev.audiobook.platform.entitlement;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record VerifiedStripeEvent(
        String eventId,
        String eventType,
        Instant eventCreated,
        String payload,
        String payloadSha256,
        JsonNode event) {
}
