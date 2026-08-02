package dev.audiobook.platform.admission.internal.inspection.work;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InspectionWorkflowService {

    ScheduledInspection schedule(UUID submissionId, UUID listenerId);

    Delivery acceptDelivery(UUID messageId, UUID workId);

    Claim claim(UUID workId, String workerId, Instant leaseUntil, String operationKey);

    List<PendingInspection> pending(Instant availableAt, int limit);

    record ScheduledInspection(UUID workId, UUID messageId) {
    }

    record Delivery(UUID workId, boolean duplicate) {
    }

    record Claim(UUID submissionId, ClaimStatus status) {
    }

    record PendingInspection(UUID workId, String operationKey) {
    }

    enum ClaimStatus {
        CLAIMED,
        RETRIES_EXHAUSTED,
        LEASED_BY_ANOTHER_WORKER,
        COMPLETED
    }
}
