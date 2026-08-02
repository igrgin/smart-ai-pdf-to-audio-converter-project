package dev.audiobook.platform.entitlement.ledger.service;

import dev.audiobook.platform.entitlement.ledger.*;

import java.time.Instant;
import java.util.UUID;

public interface ConversionEntitlementService {

    FreeGrant approveFreeGrant(UUID listenerId, String approvalReference, String idempotencyKey);

    Allowance allowance(UUID listenerId);

    AdmissionDecision authorizeSpeech(AdmissionRequest request);

    Settlement settle(SettlementRequest request);

    ResumeEligibility resumeEligibility(UUID listenerId, UUID conversionId);

    ProviderSpend providerSpend(String provider);

    Correction correctCharacters(CorrectionRequest request);

    Expiry expireFreeGrant(UUID listenerId, String evidenceReference, String idempotencyKey);

    record FreeGrant(
            UUID grantId,
            long grantedCharacters,
            Instant validFrom,
            Instant validUntil,
            boolean created) {}

    record Allowance(
            AllowanceStatus status,
            long grantedCharacters,
            long availableCharacters,
            long reservedCharacters,
            long committedCharacters,
            String denialReason,
            EntitlementSource source,
            DemonstrationSubscriptionStatus demonstrationSubscriptionStatus) {

        public Allowance(
                AllowanceStatus status,
                long grantedCharacters,
                long availableCharacters,
                long reservedCharacters,
                long committedCharacters,
                String denialReason) {
            this(
                    status,
                    grantedCharacters,
                    availableCharacters,
                    reservedCharacters,
                    committedCharacters,
                    denialReason,
                    EntitlementSource.NONE,
                    null);
        }
    }

    record AdmissionRequest(
            UUID listenerId,
            UUID conversionId,
            String provider,
            String generationRecipeReference,
            String rateCardVersion,
            long narratableCharacters,
            long conservativeProviderCostMicros,
            String idempotencyKey) {}

    record AdmissionDecision(
            boolean authorized, UUID reservationId, AdmissionDenial denial, boolean replayed) {}

    record SettlementRequest(
            UUID reservationId,
            long committedCharacters,
            long incurredProviderCostMicros,
            String idempotencyKey) {}

    record Settlement(
            UUID reservationId,
            long committedCharacters,
            long committedProviderCostMicros,
            boolean replayed) {}

    record ResumeEligibility(boolean eligible, String denialReason) {}

    record ProviderSpend(long reservedMicros, long committedMicros) {}

    record CorrectionRequest(
            UUID listenerId,
            long availableCharacterDelta,
            String evidenceReference,
            String idempotencyKey) {}

    record Correction(long availableCharacterDelta, boolean replayed) {}

    record Expiry(long expiredCharacters, boolean replayed) {}

    enum AllowanceStatus {
        NO_GRANT,
        AVAILABLE,
        EXHAUSTED,
        EXPIRED
    }

    enum EntitlementSource {
        NONE,
        FREE,
        DEMONSTRATION_SUBSCRIPTION
    }

    enum DemonstrationSubscriptionStatus {
        ACTIVE,
        CANCEL_AT_PERIOD_END,
        CANCELED,
        PAST_DUE,
        UNPAID
    }

    enum AdmissionDenial {
        NO_GRANT,
        GRANT_EXPIRED,
        INSUFFICIENT_CHARACTERS,
        PER_CONVERSION_CHARACTER_LIMIT,
        PER_CONVERSION_SPEND_LIMIT,
        LISTENER_CONCURRENCY_LIMIT,
        GLOBAL_CONCURRENCY_LIMIT,
        LISTENER_SPEND_LIMIT,
        PROVIDER_SPEND_LIMIT,
        GLOBAL_SPEND_LIMIT,
        CONVERSION_ALREADY_RESERVED
    }
}
