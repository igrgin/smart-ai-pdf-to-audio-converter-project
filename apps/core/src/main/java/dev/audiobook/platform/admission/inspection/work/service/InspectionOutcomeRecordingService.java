package dev.audiobook.platform.admission.inspection.work.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Worker-facing port that records an inspection fact without changing submission lifecycle state.
 */
public interface InspectionOutcomeRecordingService {

    Inspection inspect(InspectionCommand command);

    record InspectionCommand(
            UUID workId, String workerId, Instant leaseUntil, String operationKey) {}

    record Inspection(
            UUID submissionId, InspectionOutcome outcome, String reasonCode, boolean replayed) {}

    enum InspectionOutcome {
        ADMITTED,
        REJECTED,
        LEASED_BY_ANOTHER_WORKER
    }
}
