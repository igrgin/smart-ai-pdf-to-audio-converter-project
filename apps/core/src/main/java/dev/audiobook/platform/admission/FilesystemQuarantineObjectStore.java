package dev.audiobook.platform.admission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!prod")
public class FilesystemQuarantineObjectStore implements QuarantineObjectStore {

    private final Path root;

    public FilesystemQuarantineObjectStore(AdmissionProperties properties) throws IOException {
        root = properties.quarantinePath().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public StoredObject append(UUID objectId, long expectedOffset, byte[] bytes, boolean complete) throws IOException {
        Path path = path(objectId);
        long current = Files.exists(path) ? Files.size(path) : 0;
        if (current != expectedOffset) {
            throw new IllegalStateException("Quarantine object offset does not match the resumable session");
        }
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return metadata(objectId, path, complete);
    }

    @Override
    public StoredObject inspect(UUID objectId) throws IOException {
        Path path = path(objectId);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Quarantine object is missing");
        }
        return metadata(objectId, path, true);
    }

    @Override
    public InputStream read(UUID objectId) throws IOException {
        return Files.newInputStream(path(objectId));
    }

    @Override
    public void delete(UUID objectId) throws IOException {
        Files.deleteIfExists(path(objectId));
    }

    private StoredObject metadata(UUID objectId, Path path, boolean complete) throws IOException {
        long length = Files.size(path);
        if (!complete) {
            return new StoredObject(key(objectId), null, length, null);
        }
        String digest;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), sha256));
            }
            digest = HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        String generation = length + "-" + digest.substring(0, 16);
        return new StoredObject(key(objectId), generation, length, digest);
    }

    private Path path(UUID objectId) {
        return root.resolve(objectId + ".quarantine");
    }

    private static String key(UUID objectId) {
        return "quarantine/" + objectId;
    }
}
