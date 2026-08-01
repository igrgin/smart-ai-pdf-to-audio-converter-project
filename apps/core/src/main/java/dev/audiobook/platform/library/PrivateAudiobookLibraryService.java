package dev.audiobook.platform.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PrivateAudiobookLibraryService {

    PrivateAudiobook find(UUID listenerId, UUID conversionId);

    PlaybackManifest manifest(UUID listenerId, UUID audiobookId, UUID assetVersionId);

    MediaResponse media(
            UUID listenerId,
            UUID audiobookId,
            UUID assetVersionId,
            UUID partId,
            String rangeHeader,
            String ifRangeHeader,
            boolean headRequest);

    ResumePosition updatePosition(UpdatePosition command);

    void publish(Publication publication);

    record PrivateAudiobook(
            UUID audiobookId,
            UUID assetVersionId,
            String availability,
            String manifestDigest,
            long totalDurationMs) {
    }

    record PlaybackManifest(
            UUID audiobookId,
            UUID assetVersionId,
            UUID conversionId,
            String sourceMediaType,
            String narratorVoice,
            String manifestDigest,
            long totalDurationMs,
            ResumePosition resume,
            List<PlaybackChapter> chapters) {
        public PlaybackManifest {
            chapters = List.copyOf(chapters);
        }
    }

    record ResumePosition(long positionMs, long version) {
    }

    record UpdatePosition(
            UUID listenerId,
            UUID audiobookId,
            UUID assetVersionId,
            long positionMs,
            long expectedVersion,
            String idempotencyKey) {
    }

    record PlaybackChapter(
            UUID chapterId,
            int ordinal,
            String title,
            long startMs,
            long durationMs,
            List<PlaybackPart> parts) {
        public PlaybackChapter {
            parts = List.copyOf(parts);
        }
    }

    record PlaybackPart(
            UUID partId,
            int ordinal,
            long byteLength,
            long durationMs,
            String mimeType,
            String entityTag,
            String mediaUrl) {
    }

    record MediaResponse(
            String mimeType,
            String entityTag,
            long totalLength,
            Long rangeStart,
            Long rangeEnd,
            byte[] content) {
        public MediaResponse {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public boolean partial() {
            return rangeStart != null;
        }
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
