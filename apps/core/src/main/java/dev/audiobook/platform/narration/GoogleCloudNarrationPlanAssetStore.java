package dev.audiobook.platform.narration;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class GoogleCloudNarrationPlanAssetStore implements NarrationPlanAssetStore {

    private final Storage storage;
    private final String bucket;

    public GoogleCloudNarrationPlanAssetStore(Storage storage, NarrationProperties properties) {
        this.storage = storage;
        this.bucket = properties.workingBucket();
    }

    @Override
    public StoredAsset write(UUID conversionId, byte[] content) throws IOException {
        String reference = reference(conversionId);
        String digest = sha256(content);
        try {
            storage.create(
                    BlobInfo.newBuilder(bucket, reference)
                            .setContentType("application/json")
                            .setMetadata(java.util.Map.of("sha256", digest, "schema", "narration-plan-v1"))
                            .build(),
                    content,
                    Storage.BlobTargetOption.doesNotExist());
        } catch (StorageException exception) {
            Blob existing = storage.get(bucket, reference);
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
        if (!reference(conversionId).equals(reference)) {
            throw new IllegalArgumentException("Narration Plan Working Asset reference does not match its conversion");
        }
        Blob asset = storage.get(bucket, reference);
        if (asset == null) {
            throw new IOException("Narration Plan Working Asset is unavailable");
        }
        return asset.getContent();
    }

    private static String reference(UUID conversionId) {
        return "narration-plans/" + conversionId + "/plan-v1.json";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
