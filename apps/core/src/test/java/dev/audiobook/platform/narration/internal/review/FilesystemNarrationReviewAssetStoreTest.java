package dev.audiobook.platform.narration.internal.review;

import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.narration.internal.plan.NarrationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemNarrationReviewAssetStoreTest {

    @TempDir
    private Path workingDirectory;

    @Test
    void writesReadsAndIdempotentlyReplaysOneDecisionAsset() throws Exception {
        UUID conversionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        byte[] review = "private review".getBytes(StandardCharsets.UTF_8);
        var store = new FilesystemNarrationReviewAssetStore(
                new NarrationProperties(workingDirectory, "unused"));

        NarrationReviewAssetStore.StoredAsset first = store.write(conversionId, decisionId, review);
        NarrationReviewAssetStore.StoredAsset replay = store.write(conversionId, decisionId, review);

        assertThat(replay).isEqualTo(first);
        assertThat(store.read(conversionId, decisionId, first.reference())).isEqualTo(review);
        assertThat(first.reference()).isEqualTo(NarrationReviewAssetIdentity.reference(conversionId, decisionId));
    }

    @Test
    void rejectsConflictingContentAndMismatchedReferences() throws Exception {
        UUID conversionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        var store = new FilesystemNarrationReviewAssetStore(
                new NarrationProperties(workingDirectory, "unused"));
        store.write(conversionId, decisionId, new byte[] {1});

        assertThatThrownBy(() -> store.write(conversionId, decisionId, new byte[] {2}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different content");
        assertThatThrownBy(() -> store.read(conversionId, decisionId, "narration-plans/another/review.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
