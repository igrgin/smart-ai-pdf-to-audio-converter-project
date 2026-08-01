package dev.audiobook.platform.admission;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class GoogleCloudQuarantineObjectStore implements QuarantineObjectStore {

    private static final int MAX_COMPOSE_COMPONENTS = 32;

    private final Storage storage;
    private final String bucket;

    public GoogleCloudQuarantineObjectStore(Storage storage, AdmissionProperties properties) {
        this.storage = storage;
        this.bucket = properties.workingBucket();
    }

    @Override
    public StoredObject append(UUID objectId, long expectedOffset, byte[] bytes, boolean complete) throws IOException {
        long nextOffset = Math.addExact(expectedOffset, bytes.length);
        if (complete) {
            Blob completed = storage.get(bucket, sourceKey(objectId));
            if (completed != null) {
                if (completed.getSize() != nextOffset) {
                    throw new IllegalStateException("Completed quarantine object has an unexpected length");
                }
                return metadata(completed);
            }
        }
        String chunkName = chunkKey(objectId, expectedOffset);
        Blob existing = storage.get(bucket, chunkName);
        if (existing == null) {
            try {
                storage.create(
                        BlobInfo.newBuilder(bucket, chunkName)
                                .setContentType("application/octet-stream")
                                .build(),
                        bytes,
                        Storage.BlobTargetOption.doesNotExist());
            } catch (StorageException exception) {
                if (exception.getCode() != 412 || !sameBytes(storage.get(bucket, chunkName), bytes)) {
                    throw new IOException("Unable to append the quarantine upload", exception);
                }
            }
        } else if (!sameBytes(existing, bytes)) {
            throw new IllegalStateException("Quarantine object offset was already used by different bytes");
        }

        if (!complete) {
            return new StoredObject(sourceKey(objectId), null, nextOffset, null);
        }

        List<String> chunks = StreamSupport.stream(
                        storage.list(bucket, Storage.BlobListOption.prefix(chunkPrefix(objectId)))
                                .iterateAll()
                                .spliterator(),
                        false)
                .map(Blob::getName)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (chunks.isEmpty() || chunks.size() > MAX_COMPOSE_COMPONENTS) {
            throw new IllegalStateException("Quarantine upload has an invalid number of chunks");
        }
        Blob composed = storage.compose(Storage.ComposeRequest.newBuilder()
                .setTarget(BlobInfo.newBuilder(bucket, sourceKey(objectId))
                        .setContentType("application/octet-stream")
                        .build())
                .setTargetOptions(Storage.BlobTargetOption.doesNotExist())
                .addSource(chunks)
                .build());
        storage.delete(chunks.stream().map(name -> BlobId.of(bucket, name)).toList());
        return metadata(composed);
    }

    @Override
    public StoredObject inspect(UUID objectId) throws IOException {
        Blob blob = storage.get(bucket, sourceKey(objectId));
        if (blob == null) {
            throw new IllegalStateException("Quarantine object is missing");
        }
        return metadata(blob);
    }

    @Override
    public InputStream read(UUID objectId) throws IOException {
        ReadChannel channel = storage.reader(BlobId.of(bucket, sourceKey(objectId)));
        return Channels.newInputStream(channel);
    }

    @Override
    public void delete(UUID objectId) throws IOException {
        List<BlobId> objects = StreamSupport.stream(
                        storage.list(bucket, Storage.BlobListOption.prefix(rootPrefix(objectId)))
                                .iterateAll()
                                .spliterator(),
                        false)
                .map(Blob::getBlobId)
                .toList();
        if (!objects.isEmpty()) {
            storage.delete(objects);
        }
    }

    private StoredObject metadata(Blob blob) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = new DigestInputStream(
                Channels.newInputStream(storage.reader(blob.getBlobId())), sha256)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return new StoredObject(
                blob.getName(),
                Long.toString(blob.getGeneration()),
                blob.getSize(),
                HexFormat.of().formatHex(sha256.digest()));
    }

    private boolean sameBytes(Blob blob, byte[] expected) {
        return blob != null && java.util.Arrays.equals(blob.getContent(), expected);
    }

    private static String rootPrefix(UUID objectId) {
        return "quarantine/" + objectId + "/";
    }

    private static String chunkPrefix(UUID objectId) {
        return rootPrefix(objectId) + "chunks/";
    }

    private static String chunkKey(UUID objectId, long offset) {
        return chunkPrefix(objectId) + "%020d".formatted(offset);
    }

    private static String sourceKey(UUID objectId) {
        return rootPrefix(objectId) + "source";
    }
}
