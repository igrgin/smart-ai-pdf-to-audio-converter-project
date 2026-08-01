package dev.audiobook.platform.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PrivateAudiobookLibraryService {

    PrivateAudiobook find(UUID listenerId, UUID conversionId);

    void publish(Publication publication);

    record PrivateAudiobook(
            UUID audiobookId,
            UUID assetVersionId,
            String availability,
            String manifestDigest,
            long totalDurationMs) {
    }

    record Publication(
            UUID listenerId,
            UUID conversionId,
            UUID audiobookId,
            UUID assetVersionId,
            UUID recipeId,
            String recipeDigest,
            String manifestObjectKey,
            String manifestDigest,
            String packagingProfileVersion,
            long totalDurationMs,
            long totalBytes,
            double integratedLoudnessLufs,
            double truePeakDbtp,
            double appliedGainDb,
            Instant createdAt,
            List<Chapter> chapters) {
        public Publication {
            chapters = List.copyOf(chapters);
        }
    }

    record Chapter(
            UUID chapterId,
            int ordinal,
            String displayTitle,
            long startMs,
            long durationMs,
            List<Part> parts) {
        public Chapter {
            parts = List.copyOf(parts);
        }
    }

    record Part(
            UUID partId,
            int ordinal,
            String objectKey,
            String mimeType,
            long byteLength,
            long durationMs,
            String sha256) {
    }
}
