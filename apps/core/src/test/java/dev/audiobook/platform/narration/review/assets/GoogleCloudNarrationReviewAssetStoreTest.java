package dev.audiobook.platform.narration.review.assets;

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

import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.narration.planning.NarrationProperties;
import dev.audiobook.platform.narration.planning.assets.NarrationPlanAssetIdentity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

class GoogleCloudNarrationReviewAssetStoreTest {

    private final Storage storage = mock(Storage.class);
    private final GoogleCloudNarrationReviewAssetStore store =
            new GoogleCloudNarrationReviewAssetStore(
                    storage, new NarrationProperties(null, "working"));

    @Test
    void storesAndReadsAReviewWithAnImmutableDecisionReference() throws Exception {
        UUID conversionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        byte[] review = "private review".getBytes(StandardCharsets.UTF_8);
        String reference = NarrationReviewAssetIdentity.reference(conversionId, decisionId);
        Blob blob = mock(Blob.class);
        when(storage.get("working", reference)).thenReturn(blob);
        when(blob.getContent()).thenReturn(review);

        NarrationReviewAssetStore.StoredAsset stored =
                store.write(conversionId, decisionId, review);

        assertThat(stored.reference()).isEqualTo(reference);
        assertThat(store.read(conversionId, decisionId, reference)).isEqualTo(review);
    }

    @Test
    void acceptsOnlyAnIdenticalReplayAfterAStoragePreconditionConflict() throws Exception {
        UUID conversionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        byte[] review = new byte[] {1, 2};
        String reference = NarrationReviewAssetIdentity.reference(conversionId, decisionId);
        Blob existing = mock(Blob.class);
        when(storage.create(any(BlobInfo.class), eq(review), any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(412, "exists"));
        when(storage.get("working", reference)).thenReturn(existing);
        when(existing.getContent()).thenReturn(review);

        assertThat(store.write(conversionId, decisionId, review).sha256())
                .isEqualTo(NarrationPlanAssetIdentity.sha256(review));

        when(existing.getContent()).thenReturn(new byte[] {9});
        assertThatThrownBy(() -> store.write(conversionId, decisionId, review))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Unable to store");
    }

    @Test
    void convertsStorageFailuresMissingObjectsAndMismatchedReferencesToBoundaryFailures() {
        UUID conversionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        String reference = NarrationReviewAssetIdentity.reference(conversionId, decisionId);
        when(storage.create(
                        any(BlobInfo.class),
                        any(byte[].class),
                        any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(503, "unavailable"));

        assertThatThrownBy(() -> store.write(conversionId, decisionId, new byte[] {1}))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> store.read(conversionId, decisionId, reference))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("unavailable");
        assertThatThrownBy(
                        () ->
                                store.read(
                                        conversionId,
                                        decisionId,
                                        "narration-plans/another/review.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
