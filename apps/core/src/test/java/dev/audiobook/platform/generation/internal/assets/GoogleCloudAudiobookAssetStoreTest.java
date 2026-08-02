package dev.audiobook.platform.generation.internal.assets;

import dev.audiobook.platform.generation.internal.AudioGenerationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GoogleCloudAudiobookAssetStoreTest {

    private final Storage storage = mock(Storage.class);
    private final GoogleCloudAudiobookAssetStore store = new GoogleCloudAudiobookAssetStore(
            storage, AudioGenerationProperties.defaults(Path.of("working"), Path.of("final")));

    @Test
    void immutableConflictAcceptsOnlyByteIdenticalReplay() throws Exception {
        byte[] content = new byte[] {1, 2, 3};
        Blob existing = mock(Blob.class);
        when(storage.create(any(BlobInfo.class), eq(content), any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(412, "exists"));
        when(storage.get("local-final", "audiobooks/one/part.mp3")).thenReturn(existing);
        when(existing.getContent()).thenReturn(content);

        assertThat(store.writeFinal("audiobooks/one/part.mp3", content, "audio/mpeg").sha256())
                .matches("[0-9a-f]{64}");

        when(existing.getContent()).thenReturn(new byte[] {9});
        assertThatThrownBy(() -> store.writeFinal("audiobooks/one/part.mp3", content, "audio/mpeg"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void dependencyFailuresAndUnsafeKeysFailClosed() {
        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(503, "unavailable"));

        assertThatThrownBy(() -> store.writeWorking("conversions/one/segment.pcm", new byte[] {1}, "audio/L16"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> store.readFinal("audiobooks/missing.mp3"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> store.readWorking("../private-source"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
