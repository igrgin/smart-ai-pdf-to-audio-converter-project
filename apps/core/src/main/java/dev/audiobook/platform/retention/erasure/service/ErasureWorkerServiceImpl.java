package dev.audiobook.platform.retention.erasure.service;

import dev.audiobook.platform.admission.QuarantineObjectStore;
import dev.audiobook.platform.generation.assets.AudiobookAssetStore;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.retention.RetentionProperties;
import dev.audiobook.platform.retention.erasure.persistence.ErasureWorkerPersistence;
import dev.audiobook.platform.retention.erasure.persistence.ErasureWorkerPersistence.Obligation;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ErasureWorkerServiceImpl implements ErasureWorkerService {

    private final ErasureWorkerPersistence persistence;
    private final AudiobookAssetStore audiobookAssetStore;
    private final NarrationPlanAssetStore narrationPlanAssetStore;
    private final NarrationReviewAssetStore narrationReviewAssetStore;
    private final QuarantineObjectStore quarantineObjectStore;
    private final RetentionProperties properties;
    private final Clock identityClock;

    @Override
    @Transactional
    public int erasePending() {
        int completed = erase(persistence.claimPending());
        return completed + erase(persistence.claimEligibleRelational());
    }

    private int erase(List<Obligation> obligations) {
        int completed = 0;
        for (Obligation obligation : obligations) {
            if (erase(obligation)) {
                completed++;
            }
        }
        return completed;
    }

    private boolean erase(Obligation obligation) {
        Instant now = databaseTime(identityClock.instant());
        persistence.markErasing(obligation);
        try {
            String evidenceCode = execute(obligation);
            persistence.complete(obligation, evidenceCode);
            persistence.refreshRequest(obligation.requestId(), now);
            return true;
        } catch (RuntimeException | IOException exception) {
            int attempts = obligation.attemptCount() + 1;
            String failureCode = failureCode(exception);
            persistence.fail(obligation, failureCode);
            if (attempts >= properties.maximumAttempts()) {
                persistence.failRequest(obligation.requestId(), failureCode);
                persistence.createIncident(
                        obligation.requestId(), "ERASURE_ATTEMPTS_EXHAUSTED", now, now);
            }
            return false;
        }
    }

    private String execute(Obligation obligation) throws IOException {
        String locator = obligation.locator();
        if (locator == null || locator.isBlank()) {
            throw new IllegalStateException("Erasure locator is unavailable");
        }
        return switch (obligation.assetKind()) {
            case "AUDIO_WORKING" -> {
                audiobookAssetStore.deleteWorking(locator);
                yield "WORKING_ASSET_DELETED";
            }
            case "AUDIO_FINAL" -> {
                audiobookAssetStore.deleteFinal(locator);
                yield "FINAL_ASSET_DELETED";
            }
            case "NARRATION_PLAN" -> {
                narrationPlanAssetStore.delete(planConversion(locator), locator);
                yield "NARRATION_PLAN_DELETED";
            }
            case "NARRATION_REVIEW" -> {
                narrationReviewAssetStore.delete(
                        planConversion(locator), reviewDecision(locator), locator);
                yield "NARRATION_REVIEW_DELETED";
            }
            case "QUARANTINE_OBJECT" -> {
                quarantineObjectStore.delete(UUID.fromString(locator));
                yield "QUARANTINE_OBJECT_DELETED";
            }
            case "PROVIDER_EVIDENCE" -> {
                if (!persistence.hasQualifiedProviderEvidence(locator)) {
                    throw new IllegalStateException("Provider erasure evidence is unavailable");
                }
                yield "PROVIDER_NON_RETENTION_EVIDENCED";
            }
            case "RELATIONAL_PRIVATE_DATA" -> {
                persistence.eraseRelational(obligation.requestId(), locator);
                yield "PRIVATE_RELATIONAL_DATA_DELETED";
            }
            default -> throw new IllegalStateException("Unsupported erasure obligation kind");
        };
    }

    private static UUID planConversion(String reference) {
        String[] parts = reference.split("/");
        if (parts.length < 3 || !parts[0].equals("narration-plans")) {
            throw new IllegalArgumentException("Narration Working Asset reference is invalid");
        }
        return UUID.fromString(parts[1]);
    }

    private static UUID reviewDecision(String reference) {
        String[] parts = reference.split("/");
        if (parts.length != 4 || !parts[2].equals("reviews") || !parts[3].endsWith(".json")) {
            throw new IllegalArgumentException("Narration Review Working Asset reference is invalid");
        }
        return UUID.fromString(parts[3].substring(0, parts[3].length() - 5));
    }

    private static String failureCode(Exception exception) {
        if (exception instanceof IOException) {
            return "ASSET_STORE_UNAVAILABLE";
        }
        if (exception instanceof IllegalArgumentException) {
            return "ERASURE_COORDINATES_INVALID";
        }
        return "ERASURE_DEPENDENCY_FAILED";
    }

    private static Instant databaseTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
