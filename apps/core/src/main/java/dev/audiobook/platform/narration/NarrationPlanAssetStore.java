package dev.audiobook.platform.narration;

import java.io.IOException;
import java.util.UUID;

public interface NarrationPlanAssetStore {

    StoredAsset write(UUID conversionId, byte[] content) throws IOException;

    byte[] read(UUID conversionId, String reference) throws IOException;

    record StoredAsset(String reference, String sha256) {
    }
}
