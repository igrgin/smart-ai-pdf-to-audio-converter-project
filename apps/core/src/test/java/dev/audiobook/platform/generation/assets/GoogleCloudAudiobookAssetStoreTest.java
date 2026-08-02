package dev.audiobook.platform.generation.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import dev.audiobook.platform.generation.AudioGenerationProperties;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

class GoogleCloudAudiobookAssetStoreTest {

    private final Storage storage = mock(Storage.class);
    private final GoogleCloudAudiobookAssetStore store =
            new GoogleCloudAudiobookAssetStore(
                    storage,
                    AudioGenerationProperties.defaults(Path.of("working"), Path.of("final")));

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
        when(storage.create(
                        any(BlobInfo.class),
                        any(byte[].class),
                        any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(503, "unavailable"));

        assertThatThrownBy(
                        () ->
                                store.writeWorking(
                                        "conversions/one/segment.pcm", new byte[] {1}, "audio/L16"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> store.readFinal("audiobooks/missing.mp3"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(() -> store.readWorking("../private-source"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalErasureDeletesEveryVersionOfOnlyTheExactObject() throws Exception {
        String key = "audiobooks/one/part.mp3";
        Blob current = mock(Blob.class);
        Blob older = mock(Blob.class);
        Blob neighbor = mock(Blob.class);
        BlobId currentId = BlobId.of("local-final", key, 3L);
        BlobId olderId = BlobId.of("local-final", key, 2L);
        BlobId neighborId = BlobId.of("local-final", key + ".metadata", 1L);
        Page<Blob> versions = mock(Page.class);
        when(storage.list(eq("local-final"), any(Storage.BlobListOption[].class)))
                .thenReturn(versions);
        when(versions.iterateAll()).thenReturn(List.of(current, older, neighbor));
        when(current.getName()).thenReturn(key);
        when(current.getBlobId()).thenReturn(currentId);
        when(older.getName()).thenReturn(key);
        when(older.getBlobId()).thenReturn(olderId);
        when(neighbor.getName()).thenReturn(key + ".metadata");
        when(neighbor.getBlobId()).thenReturn(neighborId);

        store.deleteFinal(key);

        verify(storage).delete(currentId);
        verify(storage).delete(olderId);
        verify(storage, org.mockito.Mockito.never()).delete(neighborId);
    }
}
