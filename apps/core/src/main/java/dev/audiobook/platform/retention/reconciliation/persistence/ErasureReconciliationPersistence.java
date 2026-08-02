package dev.audiobook.platform.retention.reconciliation.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErasureReconciliationPersistence {

    void purgeExpiredEvidence(Instant now);

    void resolveCompletedRequestIncidents(Instant now);

    List<RequestProgress> incompleteRequests();

    void resolveIncidents(UUID requestId, Instant now);

    int createIncident(UUID requestId, String code, Instant detectedAt, Instant deadline);

    record RequestProgress(
            UUID requestId,
            Instant quickDueAt,
            Instant liveDueAt,
            Instant providerDueAt,
            int liveTotal,
            int liveCompleted,
            int providerIncomplete,
            int exhausted) {}
}
