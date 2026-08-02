package dev.audiobook.platform.retention.deletion;

import java.time.Instant;
import java.util.UUID;

public final class DeletionRequest {

    private DeletionRequest() {}

    public enum DeletionScope {
        AUDIOBOOK,
        ACCOUNT
    }

    public enum DeletionState {
        ACCEPTED,
        ERASING,
        LIVE_ERASED,
        COMPLETED,
        FAILED
    }

    public record DeleteAudiobookCommand(
            UUID listenerId, UUID audiobookId, long expectedVersion, String operationKey) {}

    public record DeleteAccountCommand(UUID listenerId, String operationKey) {}

    public record DeletionReceipt(
            UUID requestId,
            DeletionScope scope,
            DeletionState state,
            Instant requestedAt,
            Instant quickErasureDueAt,
            Instant liveErasureDueAt,
            Instant providerEvidenceDueAt,
            Instant backupExpiresAt) {}

    public record DeletionStatus(
            UUID requestId,
            DeletionScope scope,
            DeletionState state,
            Instant requestedAt,
            Instant quickErasureDueAt,
            Instant liveErasureDueAt,
            Instant providerEvidenceDueAt,
            Instant backupExpiresAt,
            int completedObligations,
            int totalObligations,
            String failureCode) {}
}
