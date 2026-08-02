package dev.audiobook.platform.narration.planning.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.planning.NarrationProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

class FilesystemNarrationPlanAssetStoreTest {

    @TempDir private Path workingDirectory;

    @Test
    void writesReadsAndIdempotentlyReplaysOneConversionAsset() throws Exception {
        UUID conversionId = UUID.randomUUID();
        byte[] plan = "private plan".getBytes(StandardCharsets.UTF_8);
        var store =
                new FilesystemNarrationPlanAssetStore(
                        new NarrationProperties(workingDirectory, "unused"));

        NarrationPlanAssetStore.StoredAsset first = store.write(conversionId, plan);
        NarrationPlanAssetStore.StoredAsset replay = store.write(conversionId, plan);

        assertThat(replay).isEqualTo(first);
        assertThat(store.read(conversionId, first.reference())).isEqualTo(plan);
        assertThat(first.reference())
                .isEqualTo("narration-plans/" + conversionId + "/plan-v1.json");

        store.delete(conversionId, first.reference());
        store.delete(conversionId, first.reference());
        assertThatThrownBy(() -> store.read(conversionId, first.reference()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsConflictingContentAndCrossConversionReferences() throws Exception {
        UUID conversionId = UUID.randomUUID();
        var store =
                new FilesystemNarrationPlanAssetStore(
                        new NarrationProperties(workingDirectory, "unused"));
        store.write(conversionId, new byte[] {1});

        assertThatThrownBy(() -> store.write(conversionId, new byte[] {2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content");
        assertThatThrownBy(() -> store.read(conversionId, "narration-plans/another/plan-v1.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
