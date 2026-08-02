package dev.audiobook.platform.retention.erasure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErasureWorkerPersistence {

    List<Obligation> claimPending();

    List<Obligation> claimEligibleRelational();

    void markErasing(Obligation obligation);

    void complete(Obligation obligation, String evidenceCode);

    void fail(Obligation obligation, String failureCode);

    void failRequest(UUID requestId, String failureCode);

    boolean hasQualifiedProviderEvidence(String operationId);

    void eraseRelational(UUID requestId, String locator);

    void refreshRequest(UUID requestId, Instant completedAt);

    void createIncident(UUID requestId, String code, Instant detectedAt, Instant deadline);

    record Obligation(
            UUID obligationId,
            UUID requestId,
            String assetKind,
            String locator,
            int attemptCount) {}
}
