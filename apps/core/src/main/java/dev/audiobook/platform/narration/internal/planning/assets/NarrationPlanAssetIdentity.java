package dev.audiobook.platform.narration.internal.planning.assets;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class NarrationPlanAssetIdentity {

    private NarrationPlanAssetIdentity() {
    }

    public static String reference(UUID conversionId) {
        return "narration-plans/" + conversionId + "/plan-v1.json";
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
