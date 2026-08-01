package dev.audiobook.platform.generation;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class GoogleCloudAudiobookAssetStore implements AudiobookAssetStore {

    private final Storage storage;
    private final AudioGenerationProperties properties;

    @Override
    public StoredAsset writeWorking(String objectKey, byte[] content, String contentType) throws IOException {
        return write(properties.workingBucket(), objectKey, content, contentType);
    }

    @Override
    public byte[] readWorking(String objectKey) throws IOException {
        return read(properties.workingBucket(), objectKey);
    }

    @Override
    public StoredAsset writeFinal(String objectKey, byte[] content, String contentType) throws IOException {
        return write(properties.finalBucket(), objectKey, content, contentType);
    }

    @Override
    public byte[] readFinal(String objectKey) throws IOException {
        return read(properties.finalBucket(), objectKey);
    }

    private StoredAsset write(String bucket, String key, byte[] content, String contentType) throws IOException {
        validate(key, content);
        String digest = SpeechSegmentationServiceImpl.sha256Bytes(content);
        BlobInfo info = BlobInfo.newBuilder(bucket, key)
                .setContentType(contentType)
                .setCacheControl("private, no-store")
                .setMetadata(Map.of("sha256", digest))
                .build();
        try {
            storage.create(info, content, Storage.BlobTargetOption.doesNotExist());
        } catch (StorageException exception) {
            Blob existing = storage.get(bucket, key);
            if (exception.getCode() != 412
                    || existing == null
                    || !MessageDigest.isEqual(existing.getContent(), content)) {
                throw new IOException("Unable to write immutable audiobook asset", exception);
            }
        }
        return new StoredAsset(key, digest, content.length);
    }

    private byte[] read(String bucket, String key) throws IOException {
        validateKey(key);
        Blob blob = storage.get(bucket, key);
        if (blob == null) {
            throw new IOException("Audiobook asset is unavailable");
        }
        return blob.getContent();
    }

    private static void validate(String key, byte[] content) {
        validateKey(key);
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Asset content must not be empty");
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
            throw new IllegalArgumentException("Asset object key is invalid");
        }
    }
}
