package dev.audiobook.platform.admission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PublicationSubmissionService {

    Creation create(CreateCommand command);

    UploadProgress upload(UploadCommand command);

    Submission confirm(ConfirmCommand command);

    Submission cancel(CancelCommand command);

    int expireDue();

    Delivery acceptInspectionDelivery(UUID messageId, UUID workId);

    Inspection inspect(InspectionCommand command);

    Submission submission(UUID listenerId, UUID submissionId);

    List<AudiobookConversion> conversions(UUID listenerId);

    record CreateCommand(
            UUID listenerId,
            String mediaType,
            long byteLength,
            String sha256,
            String termsVersion,
            String noticeVersion,
            String idempotencyKey) {
    }

    record Creation(
            UUID submissionId,
            SubmissionState state,
            UploadSession uploadSession,
            boolean created) {
    }

    record UploadSession(String token, Instant expiresAt, int chunkSize) {
    }

    record UploadCommand(
            UUID submissionId,
            String token,
            long offset,
            long totalBytes,
            String chunkSha256,
            byte[] bytes) {
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record UploadProgress(long nextOffset, boolean complete, String storageGeneration) {
    }

    record ConfirmCommand(
            UUID listenerId,
            UUID submissionId,
            String storageGeneration,
            long byteLength,
            String sha256,
            String idempotencyKey) {
    }

    record CancelCommand(UUID listenerId, UUID submissionId, String idempotencyKey) {
    }

    record InspectionCommand(UUID workId, String workerId, Instant leaseUntil, String operationKey) {
    }

    record Delivery(UUID workId, boolean duplicate) {
    }

    record Inspection(
            UUID submissionId,
            InspectionOutcome outcome,
            String reasonCode,
            UUID sourcePublicationId,
            UUID conversionId,
            boolean replayed) {
    }

    record Submission(
            UUID submissionId,
            SubmissionState state,
            String reasonCode,
            UUID conversionId) {
    }

    record AudiobookConversion(UUID conversionId, ConversionState state) {
    }

    enum SubmissionState {
        AWAITING_UPLOAD,
        UPLOADED,
        INSPECTING,
        ADMITTED,
        REJECTED,
        EXPIRED,
        CANCELLED
    }

    enum InspectionOutcome {
        ADMITTED,
        REJECTED,
        LEASED_BY_ANOTHER_WORKER
    }

    enum ConversionState {
        PREPARING
    }
}
