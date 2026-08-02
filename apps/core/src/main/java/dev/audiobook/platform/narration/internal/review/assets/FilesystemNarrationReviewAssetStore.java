package dev.audiobook.platform.narration.internal.review.assets;

import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.narration.internal.planning.NarrationProperties;

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
public class FilesystemNarrationReviewAssetStore implements NarrationReviewAssetStore {

    private final Path root;

    public FilesystemNarrationReviewAssetStore(NarrationProperties properties) throws IOException {
        root = properties.workingAssetPath().toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public StoredAsset write(UUID conversionId, UUID decisionId, byte[] content) throws IOException {
        NarrationReviewAssetIdentity.Identity identity =
                NarrationReviewAssetIdentity.identify(conversionId, decisionId, content);
        Path target = path(conversionId, decisionId);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (!MessageDigest.isEqual(existing, content)) {
                throw new IllegalStateException("Narration Review Working Asset already has different content");
            }
            return new StoredAsset(identity.reference(), identity.sha256());
        }
        Path temporary = Files.createTempFile(target.getParent(), "review-", ".pending");
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
        return new StoredAsset(identity.reference(), identity.sha256());
    }

    @Override
    public byte[] read(UUID conversionId, UUID decisionId, String reference) throws IOException {
        NarrationReviewAssetIdentity.requireReference(conversionId, decisionId, reference);
        return Files.readAllBytes(path(conversionId, decisionId));
    }

    private Path path(UUID conversionId, UUID decisionId) {
        return root.resolve(conversionId.toString()).resolve("reviews").resolve(decisionId + ".json");
    }
}
