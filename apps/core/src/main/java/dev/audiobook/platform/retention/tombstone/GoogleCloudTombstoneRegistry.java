package dev.audiobook.platform.retention.tombstone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import dev.audiobook.platform.retention.RetentionProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@Profile("prod")
@RequiredArgsConstructor
public class GoogleCloudTombstoneRegistry implements TombstoneRegistry {

    private static final String PREFIX = "deletion-tombstones/";

    private final Storage storage;
    private final RetentionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public void append(TombstoneRecord tombstone) {
        String objectKey = PREFIX + tombstone.tombstoneId() + ".json";
        try {
            byte[] content = objectMapper.writeValueAsBytes(tombstone);
            BlobInfo info =
                    BlobInfo.newBuilder(properties.tombstoneRegistryBucket(), objectKey)
                            .setContentType("application/json")
                            .setCacheControl("private, no-store")
                            .setMetadata(Map.of("content", "content-free-deletion-tombstone"))
                            .build();
            try {
                storage.create(info, content, Storage.BlobTargetOption.doesNotExist());
            } catch (StorageException replay) {
                Blob existing =
                        storage.get(properties.tombstoneRegistryBucket(), objectKey);
                if (replay.getCode() != 412
                        || existing == null
                        || !MessageDigest.isEqual(existing.getContent(), content)) {
                    throw replay;
                }
            }
        } catch (IOException | StorageException exception) {
            throw new IllegalStateException("Tombstone registry is unavailable", exception);
        }
    }

    @Override
    public List<TombstoneRecord> entries() {
        try {
            return java.util.stream.StreamSupport.stream(
                            storage.list(
                                            properties.tombstoneRegistryBucket(),
                                            Storage.BlobListOption.prefix(PREFIX))
                                    .iterateAll()
                                    .spliterator(),
                            false)
                    .map(this::read)
                    .sorted(Comparator.comparing(TombstoneRecord::createdAt))
                    .toList();
        } catch (StorageException exception) {
            throw new IllegalStateException("Tombstone registry is unavailable", exception);
        }
    }

    private TombstoneRecord read(Blob blob) {
        try {
            return objectMapper.readValue(blob.getContent(), TombstoneRecord.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Tombstone registry entry is invalid", exception);
        }
    }
}
