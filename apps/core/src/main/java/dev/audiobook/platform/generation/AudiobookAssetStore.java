package dev.audiobook.platform.generation;

import java.io.IOException;

public interface AudiobookAssetStore {

    StoredAsset writeWorking(String objectKey, byte[] content, String contentType) throws IOException;

    byte[] readWorking(String objectKey) throws IOException;

    StoredAsset writeFinal(String objectKey, byte[] content, String contentType) throws IOException;

    byte[] readFinal(String objectKey) throws IOException;

    record StoredAsset(String objectKey, String sha256, long byteLength) {
    }
}
