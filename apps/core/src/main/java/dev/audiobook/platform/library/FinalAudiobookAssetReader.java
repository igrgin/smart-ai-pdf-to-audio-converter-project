package dev.audiobook.platform.library;

import java.io.IOException;

public interface FinalAudiobookAssetReader {

    byte[] readFinal(String objectKey) throws IOException;
}
