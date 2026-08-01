package dev.audiobook.platform.narration;

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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleCloudNarrationPlanAssetStoreTest {

    private final Storage storage = mock(Storage.class);
    private final GoogleCloudNarrationPlanAssetStore store = new GoogleCloudNarrationPlanAssetStore(
            storage, new NarrationProperties(null, "working"));

    @Test
    void storesAndReadsAPlanWithAnImmutableReference() throws Exception {
        UUID conversionId = UUID.randomUUID();
        byte[] plan = "private plan".getBytes(StandardCharsets.UTF_8);
        Blob blob = mock(Blob.class);
        when(storage.get("working", NarrationPlanAssetIdentity.reference(conversionId))).thenReturn(blob);
        when(blob.getContent()).thenReturn(plan);

        NarrationPlanAssetStore.StoredAsset stored = store.write(conversionId, plan);

        assertThat(stored.reference()).isEqualTo(NarrationPlanAssetIdentity.reference(conversionId));
        assertThat(store.read(conversionId, stored.reference())).isEqualTo(plan);
    }

    @Test
    void acceptsOnlyAnIdenticalReplayAfterAStoragePreconditionConflict() throws Exception {
        UUID conversionId = UUID.randomUUID();
        byte[] plan = new byte[] {1, 2};
        Blob existing = mock(Blob.class);
        when(storage.create(any(BlobInfo.class), eq(plan), any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(412, "exists"));
        when(storage.get("working", NarrationPlanAssetIdentity.reference(conversionId))).thenReturn(existing);
        when(existing.getContent()).thenReturn(plan);

        assertThat(store.write(conversionId, plan).sha256())
                .isEqualTo(NarrationPlanAssetIdentity.sha256(plan));

        when(existing.getContent()).thenReturn(new byte[] {9});
        assertThatThrownBy(() -> store.write(conversionId, plan))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Unable to store");
    }

    @Test
    void convertsStorageFailuresAndMissingObjectsToDependencyFailures() {
        UUID conversionId = UUID.randomUUID();
        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(503, "unavailable"));

        assertThatThrownBy(() -> store.write(conversionId, new byte[] {1}))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> store.read(
                        conversionId, NarrationPlanAssetIdentity.reference(conversionId)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("unavailable");
    }
}
