package dev.audiobook.platform.workflow;

import java.time.Instant;
import java.util.UUID;

public interface InspectionWorkflowService {

    ScheduledInspection schedule(UUID submissionId, UUID listenerId);

    Delivery acceptDelivery(UUID messageId, UUID workId);

    Claim claim(UUID workId, String workerId, Instant leaseUntil, String operationKey);

    boolean complete(UUID workId, String workerId);

    record ScheduledInspection(UUID workId, UUID messageId) {
    }

    record Delivery(UUID workId, boolean duplicate) {
    }

    record Claim(UUID submissionId, ClaimStatus status) {
    }

    enum ClaimStatus {
        CLAIMED,
        LEASED_BY_ANOTHER_WORKER,
        COMPLETED
    }
}
