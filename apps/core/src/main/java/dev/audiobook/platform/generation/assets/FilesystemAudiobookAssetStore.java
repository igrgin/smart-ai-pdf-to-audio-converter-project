package dev.audiobook.platform.generation.assets;

import dev.audiobook.platform.generation.AudioGenerationProperties;
import dev.audiobook.platform.generation.shared.digest.Sha256Digest;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

@Component
@Profile("!prod")
public class FilesystemAudiobookAssetStore implements AudiobookAssetStore {

    private final Path workingRoot;
    private final Path finalRoot;

    public FilesystemAudiobookAssetStore(AudioGenerationProperties properties) throws IOException {
        workingRoot = normalizedRoot(properties.workingAssetPath());
        finalRoot = normalizedRoot(properties.finalAssetPath());
        Files.createDirectories(workingRoot);
        Files.createDirectories(finalRoot);
    }

    @Override
    public StoredAsset writeWorking(String objectKey, byte[] content, String contentType)
            throws IOException {
        return write(workingRoot, objectKey, content);
    }

    @Override
    public byte[] readWorking(String objectKey) throws IOException {
        return Files.readAllBytes(resolve(workingRoot, objectKey));
    }

    @Override
    public StoredAsset writeFinal(String objectKey, byte[] content, String contentType)
            throws IOException {
        return write(finalRoot, objectKey, content);
    }

    @Override
    public byte[] readFinal(String objectKey) throws IOException {
        return Files.readAllBytes(resolve(finalRoot, objectKey));
    }

    private static StoredAsset write(Path root, String objectKey, byte[] content)
            throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Asset content must not be empty");
        }
        Path target = resolve(root, objectKey);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (!MessageDigest.isEqual(existing, content)) {
                throw new IllegalStateException("Immutable asset already has different content");
            }
            return stored(objectKey, existing);
        }
        Path temporary = Files.createTempFile(target.getParent(), "asset-", ".pending");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return stored(objectKey, content);
    }

    private static StoredAsset stored(String objectKey, byte[] content) {
        return new StoredAsset(objectKey, Sha256Digest.of(content), content.length);
    }

    private static Path normalizedRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Asset root is required");
        }
        return root.toAbsolutePath().normalize();
    }

    private static Path resolve(Path root, String objectKey) {
        if (objectKey == null
                || objectKey.isBlank()
                || objectKey.startsWith("/")
                || objectKey.contains("..")
                || !objectKey.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("Asset object key is invalid");
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Asset object key leaves its storage root");
        }
        return resolved;
    }
}
