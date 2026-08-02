package dev.audiobook.platform.offline.authorization.service;

import dev.audiobook.platform.offline.authorization.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OfflineAccessService {

    OfflineCopyAuthorization issue(IssueAuthorization command);

    void revoke(UUID listenerId, UUID audiobookId);

    record IssueAuthorization(
            UUID listenerId,
            UUID installationId,
            UUID audiobookId,
            UUID assetVersionId,
            String idempotencyKey) {}

    record OfflineCopyAuthorization(
            Instant serverTime, SignedAuthorization authorization, OfflineManifest manifest) {}

    record SignedAuthorization(
            String algorithm,
            String keyId,
            String publicKey,
            String payload,
            String signature,
            AuthorizationClaims claims) {}

    record AuthorizationClaims(
            UUID listenerId,
            UUID installationId,
            UUID audiobookId,
            UUID assetVersionId,
            long authorizationGeneration,
            String purpose,
            Instant issuedAt,
            Instant expiresAt) {}

    record OfflineManifest(
            UUID audiobookId,
            UUID assetVersionId,
            String manifestDigest,
            String sourceMediaType,
            String narratorVoice,
            long totalDurationMs,
            long totalBytes,
            List<OfflineChapter> chapters,
            List<OfflinePart> parts) {
        public OfflineManifest {
            chapters = List.copyOf(chapters);
            parts = List.copyOf(parts);
        }
    }

    record OfflineChapter(
            UUID chapterId,
            int ordinal,
            String title,
            long startMs,
            long durationMs,
            List<UUID> partIds) {
        public OfflineChapter {
            partIds = List.copyOf(partIds);
        }
    }

    record OfflinePart(
            UUID partId,
            int ordinal,
            String mimeType,
            long byteLength,
            long durationMs,
            String entityTag,
            String mediaUrl,
            List<OfflineChunk> chunks) {
        public OfflinePart {
            chunks = List.copyOf(chunks);
        }
    }

    record OfflineChunk(int ordinal, long start, long end, int byteLength, String sha256) {}
}
