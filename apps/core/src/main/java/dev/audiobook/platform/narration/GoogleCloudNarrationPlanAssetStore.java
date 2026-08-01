package dev.audiobook.platform.narration;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class GoogleCloudNarrationPlanAssetStore implements NarrationPlanAssetStore {

    private final Storage storage;
    private final NarrationProperties properties;

    @Override
    public StoredAsset write(UUID conversionId, byte[] content) throws IOException {
        String reference = NarrationPlanAssetIdentity.reference(conversionId);
        String digest = NarrationPlanAssetIdentity.sha256(content);
        try {
            storage.create(
                    BlobInfo.newBuilder(properties.workingBucket(), reference)
                            .setContentType("application/json")
                            .setMetadata(java.util.Map.of("sha256", digest, "schema", "narration-plan-v1"))
                            .build(),
                    content,
                    Storage.BlobTargetOption.doesNotExist());
        } catch (StorageException exception) {
            Blob existing = storage.get(properties.workingBucket(), reference);
            if (exception.getCode() != 412
                    || existing == null
                    || !MessageDigest.isEqual(existing.getContent(), content)) {
                throw new IOException("Unable to store the Narration Plan Working Asset", exception);
            }
        }
        return new StoredAsset(reference, digest);
    }

    @Override
    public byte[] read(UUID conversionId, String reference) throws IOException {
        if (!NarrationPlanAssetIdentity.reference(conversionId).equals(reference)) {
            throw new IllegalArgumentException("Narration Plan Working Asset reference does not match its conversion");
        }
        Blob asset = storage.get(properties.workingBucket(), reference);
        if (asset == null) {
            throw new IOException("Narration Plan Working Asset is unavailable");
        }
        return asset.getContent();
    }

}
