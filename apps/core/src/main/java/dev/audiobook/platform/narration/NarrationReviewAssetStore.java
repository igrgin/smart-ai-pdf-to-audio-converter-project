package dev.audiobook.platform.narration;

import java.io.IOException;
import java.util.UUID;

public interface NarrationReviewAssetStore {

    static String reference(UUID conversionId, UUID decisionId) {
        return "narration-plans/" + conversionId + "/reviews/" + decisionId + ".json";
    }

    StoredAsset write(UUID conversionId, UUID decisionId, byte[] content) throws IOException;

    byte[] read(UUID conversionId, UUID decisionId, String reference) throws IOException;

    record StoredAsset(String reference, String sha256) {
    }
}
