package dev.audiobook.platform.generation.assets;

import dev.audiobook.platform.library.FinalAudiobookAssetReader;

import java.io.IOException;

public interface AudiobookAssetStore extends FinalAudiobookAssetReader {

    StoredAsset writeWorking(String objectKey, byte[] content, String contentType)
            throws IOException;

    byte[] readWorking(String objectKey) throws IOException;

    StoredAsset writeFinal(String objectKey, byte[] content, String contentType) throws IOException;

    record StoredAsset(String objectKey, String sha256, long byteLength) {}
}
