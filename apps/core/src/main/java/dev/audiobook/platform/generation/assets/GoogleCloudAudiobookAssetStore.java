package dev.audiobook.platform.generation.assets;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import dev.audiobook.platform.generation.AudioGenerationProperties;
import dev.audiobook.platform.generation.shared.digest.Sha256Digest;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Map;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class GoogleCloudAudiobookAssetStore implements AudiobookAssetStore {

    private final Storage storage;
    private final AudioGenerationProperties properties;

    @Override
    public StoredAsset writeWorking(String objectKey, byte[] content, String contentType)
            throws IOException {
        return write(properties.workingBucket(), objectKey, content, contentType);
    }

    @Override
    public byte[] readWorking(String objectKey) throws IOException {
        return read(properties.workingBucket(), objectKey);
    }

    @Override
    public void deleteWorking(String objectKey) throws IOException {
        delete(properties.workingBucket(), objectKey);
    }

    @Override
    public StoredAsset writeFinal(String objectKey, byte[] content, String contentType)
            throws IOException {
        return write(properties.finalBucket(), objectKey, content, contentType);
    }

    @Override
    public byte[] readFinal(String objectKey) throws IOException {
        return read(properties.finalBucket(), objectKey);
    }

    @Override
    public void deleteFinal(String objectKey) throws IOException {
        deleteAllVersions(properties.finalBucket(), objectKey);
    }

    private StoredAsset write(String bucket, String key, byte[] content, String contentType)
            throws IOException {
        validate(key, content);
        String digest = Sha256Digest.of(content);
        BlobInfo info =
                BlobInfo.newBuilder(bucket, key)
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

    private void delete(String bucket, String key) throws IOException {
        validateKey(key);
        try {
            storage.delete(bucket, key);
        } catch (StorageException exception) {
            throw new IOException("Unable to delete audiobook asset", exception);
        }
    }

    private void deleteAllVersions(String bucket, String key) throws IOException {
        validateKey(key);
        try {
            for (Blob blob :
                    storage.list(
                                    bucket,
                                    Storage.BlobListOption.prefix(key),
                                    Storage.BlobListOption.versions(true))
                            .iterateAll()) {
                if (blob.getName().equals(key)) {
                    storage.delete(blob.getBlobId());
                }
            }
        } catch (StorageException exception) {
            throw new IOException("Unable to delete every audiobook asset generation", exception);
        }
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
