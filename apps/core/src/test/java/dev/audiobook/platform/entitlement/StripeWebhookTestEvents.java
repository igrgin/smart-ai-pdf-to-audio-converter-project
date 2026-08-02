package dev.audiobook.platform.entitlement;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class StripeWebhookTestEvents {

    private StripeWebhookTestEvents() {
    }

    public static String signature(String payload, String secret) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (Exception unavailable) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable to the test", unavailable);
        }
    }
}
