package dev.audiobook.platform.entitlement.internal.stripe;

import dev.audiobook.platform.entitlement.internal.subscription.DemonstrationSubscriptionProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StripeWebhookVerifierImpl implements StripeWebhookVerifier {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DemonstrationSubscriptionProperties properties;
    private final Clock clock;

    @Override
    public VerifiedStripeEvent verify(String payload, String signatureHeader) {
        if (payload == null || payload.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
            throw new StripeWebhookVerificationException("Stripe payload and signature are required");
        }
        StripeSignature signature = parseSignature(signatureHeader);
        long age = Math.abs(clock.instant().getEpochSecond() - signature.timestamp());
        if (age > properties.signatureTolerance().toSeconds()) {
            throw new StripeWebhookVerificationException("Stripe signature timestamp is outside the tolerance");
        }

        byte[] expected = hmac(signature.timestamp() + "." + payload, properties.webhookSecret());
        boolean authentic = signature.v1Signatures().stream()
                .map(StripeWebhookVerifierImpl::decodeHex)
                .anyMatch(candidate -> MessageDigest.isEqual(expected, candidate));
        if (!authentic) {
            throw new StripeWebhookVerificationException("Stripe signature is invalid");
        }

        JsonNode event = parsePayload(payload);
        if (!"event".equals(text(event, "object"))) {
            throw new StripeWebhookVerificationException("Stripe payload is not an event");
        }
        if (event.path("livemode").asBoolean(true)) {
            throw new StripeWebhookVerificationException("Live-mode Stripe events are not accepted");
        }
        String eventId = requiredText(event, "id");
        String eventType = requiredText(event, "type");
        long created = event.path("created").asLong(0);
        if (created <= 0 || !event.path("data").path("object").isObject()) {
            throw new StripeWebhookVerificationException("Stripe event envelope is incomplete");
        }
        return new VerifiedStripeEvent(
                eventId,
                eventType,
                Instant.ofEpochSecond(created),
                payload,
                sha256(payload),
                event);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException invalidJson) {
            throw new StripeWebhookVerificationException("Stripe payload is not valid JSON", invalidJson);
        }
    }

    private static StripeSignature parseSignature(String header) {
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if ("t".equals(pair[0])) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException invalidTimestamp) {
                    throw new StripeWebhookVerificationException("Stripe signature timestamp is invalid");
                }
            } else if ("v1".equals(pair[0])) {
                signatures.add(pair[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new StripeWebhookVerificationException("Stripe signature header is incomplete");
        }
        return new StripeSignature(timestamp, signatures);
    }

    private static byte[] hmac(String signedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", unavailable);
        }
    }

    private static byte[] decodeHex(String value) {
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException invalidHex) {
            return new byte[0];
        }
    }

    private static String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new StripeWebhookVerificationException("Stripe event " + field + " is invalid");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private record StripeSignature(long timestamp, List<String> v1Signatures) {
    }
}
