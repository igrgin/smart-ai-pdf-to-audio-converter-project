package dev.audiobook.platform.generation.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.generation.AudioGenerationProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FilesystemAudiobookAssetStoreTest {

    @TempDir private Path temporaryDirectory;

    @Test
    void workingAndFinalWritesAreImmutableAndVerified() throws Exception {
        AudioGenerationProperties properties =
                AudioGenerationProperties.defaults(
                        temporaryDirectory.resolve("working"), temporaryDirectory.resolve("final"));
        AudiobookAssetStore store = new FilesystemAudiobookAssetStore(properties);

        AudiobookAssetStore.StoredAsset working =
                store.writeWorking(
                        "conversions/opaque/segments/one.pcm",
                        new byte[] {1, 2, 3, 4},
                        "audio/L16");
        AudiobookAssetStore.StoredAsset replay =
                store.writeWorking(
                        "conversions/opaque/segments/one.pcm",
                        new byte[] {1, 2, 3, 4},
                        "audio/L16");
        AudiobookAssetStore.StoredAsset finalized =
                store.writeFinal(
                        "audiobooks/opaque/parts/one.mp3", new byte[] {5, 6, 7}, "audio/mpeg");

        assertThat(replay).isEqualTo(working);
        assertThat(store.readWorking(working.objectKey())).containsExactly(1, 2, 3, 4);
        assertThat(store.readFinal(finalized.objectKey())).containsExactly(5, 6, 7);
        assertThat(working.sha256()).matches("[0-9a-f]{64}");
        assertThat(finalized.byteLength()).isEqualTo(3);
        assertThatThrownBy(
                        () -> store.writeFinal(finalized.objectKey(), new byte[] {9}, "audio/mpeg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content");
        assertThatThrownBy(() -> store.readWorking("../private-source"))
                .isInstanceOf(IllegalArgumentException.class);

        store.deleteWorking(working.objectKey());
        store.deleteWorking(working.objectKey());
        store.deleteFinal(finalized.objectKey());
        store.deleteFinal(finalized.objectKey());
        assertThatThrownBy(() -> store.readWorking(working.objectKey()))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> store.readFinal(finalized.objectKey()))
                .isInstanceOf(java.io.IOException.class);
    }
}
