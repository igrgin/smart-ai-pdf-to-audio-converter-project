package dev.audiobook.platform.library;

import dev.audiobook.platform.library.service.*;

import java.io.IOException;

public interface FinalAudiobookAssetReader {

    byte[] readFinal(String objectKey) throws IOException;
}
