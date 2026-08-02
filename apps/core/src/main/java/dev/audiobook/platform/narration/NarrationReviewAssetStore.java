package dev.audiobook.platform.narration;

import java.io.IOException;
import java.util.UUID;

public interface NarrationReviewAssetStore {

    StoredAsset write(UUID conversionId, UUID decisionId, byte[] content) throws IOException;

    byte[] read(UUID conversionId, UUID decisionId, String reference) throws IOException;

    void delete(UUID conversionId, UUID decisionId, String reference) throws IOException;

    record StoredAsset(String reference, String sha256) {
    }
}
