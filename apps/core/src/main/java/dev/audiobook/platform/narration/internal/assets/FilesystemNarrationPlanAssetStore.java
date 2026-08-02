package dev.audiobook.platform.narration.internal.assets;

import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.internal.plan.NarrationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class FilesystemNarrationPlanAssetStore implements NarrationPlanAssetStore {

    private final Path root;

    public FilesystemNarrationPlanAssetStore(NarrationProperties properties) throws IOException {
        root = properties.workingAssetPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public StoredAsset write(UUID conversionId, byte[] content) throws IOException {
        String reference = NarrationPlanAssetIdentity.reference(conversionId);
        String digest = NarrationPlanAssetIdentity.sha256(content);
        Path target = path(conversionId);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (!MessageDigest.isEqual(existing, content)) {
                throw new IllegalStateException("Narration Plan Working Asset already has different content");
            }
            return new StoredAsset(reference, digest);
        }
        Path temporary = Files.createTempFile(target.getParent(), "plan-", ".pending");
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
        return new StoredAsset(reference, digest);
    }

    @Override
    public byte[] read(UUID conversionId, String reference) throws IOException {
        if (!NarrationPlanAssetIdentity.reference(conversionId).equals(reference)) {
            throw new IllegalArgumentException("Narration Plan Working Asset reference does not match its conversion");
        }
        return Files.readAllBytes(path(conversionId));
    }

    private Path path(UUID conversionId) {
        return root.resolve(conversionId.toString()).resolve("plan-v1.json");
    }

}
