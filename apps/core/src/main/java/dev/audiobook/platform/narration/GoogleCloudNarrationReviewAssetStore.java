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
public class GoogleCloudNarrationReviewAssetStore implements NarrationReviewAssetStore {

    private final Storage storage;
    private final NarrationProperties properties;

    @Override
    public StoredAsset write(UUID conversionId, UUID decisionId, byte[] content) throws IOException {
        NarrationReviewAssetIdentity.Identity identity =
                NarrationReviewAssetIdentity.identify(conversionId, decisionId, content);
        try {
            storage.create(
                    BlobInfo.newBuilder(properties.workingBucket(), identity.reference())
                            .setContentType("application/json")
                            .setMetadata(java.util.Map.of(
                                    "sha256", identity.sha256(), "schema", "narration-review-v1"))
                            .build(),
                    content,
                    Storage.BlobTargetOption.doesNotExist());
        } catch (StorageException exception) {
            Blob existing = storage.get(properties.workingBucket(), identity.reference());
            if (exception.getCode() != 412
                    || existing == null
                    || !MessageDigest.isEqual(existing.getContent(), content)) {
                throw new IOException("Unable to store the Narration Review Working Asset", exception);
            }
        }
        return new StoredAsset(identity.reference(), identity.sha256());
    }

    @Override
    public byte[] read(UUID conversionId, UUID decisionId, String reference) throws IOException {
        NarrationReviewAssetIdentity.requireReference(conversionId, decisionId, reference);
        Blob asset = storage.get(properties.workingBucket(), reference);
        if (asset == null) {
            throw new IOException("Narration Review Working Asset is unavailable");
        }
        return asset.getContent();
    }
}
