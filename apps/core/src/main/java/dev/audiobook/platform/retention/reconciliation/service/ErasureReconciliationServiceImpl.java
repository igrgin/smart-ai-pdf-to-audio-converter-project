package dev.audiobook.platform.retention.reconciliation.service;

import dev.audiobook.platform.retention.reconciliation.persistence.ErasureReconciliationPersistence;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ErasureReconciliationServiceImpl implements ErasureReconciliationService {

    private final ErasureReconciliationPersistence persistence;
    private final Clock identityClock;

    @Override
    @Transactional
    public int reconcile() {
        Instant now = identityClock.instant();
        persistence.purgeExpiredEvidence(now);
        persistence.resolveCompletedRequestIncidents(now);
        int incidents = 0;
        for (var request : persistence.incompleteRequests()) {
            var progress =
                    new ErasureDeadlinePolicy.Progress(
                            request.requestId(),
                            request.quickDueAt(),
                            request.liveDueAt(),
                            request.providerDueAt(),
                            request.liveTotal(),
                            request.liveCompleted(),
                            request.providerIncomplete(),
                            request.exhausted());
            persistence.resolveIncidents(request.requestId(), now);
            for (var incident : ErasureDeadlinePolicy.incidents(progress, now)) {
                incidents +=
                        persistence.createIncident(
                                request.requestId(), incident.code(), now, incident.deadline());
            }
        }
        return incidents;
    }
}
