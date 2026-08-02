package dev.audiobook.platform.bootstrap.composition.entitlement;

import dev.audiobook.platform.entitlement.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.internal.signin.ExternalIdentity;
import dev.audiobook.platform.identity.internal.listener.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import java.net.URI;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@Transactional
class ConversionEntitlementServiceITest {

    private final ConversionEntitlementService entitlementService;
    private final ListenerIdentityService listenerIdentityService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ConversionEntitlementServiceITest(
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService,
            JdbcTemplate jdbcTemplate) {
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void approvalBackedFreeGrantProducesOneDerivedNonRenewingAllowance() {
        UUID listenerId = listener("grant");

        ConversionEntitlementService.FreeGrant first = entitlementService.approveFreeGrant(
                listenerId, "approval-case-22", "grant-operation-22");
        ConversionEntitlementService.FreeGrant replay = entitlementService.approveFreeGrant(
                listenerId, "approval-case-22", "grant-operation-22");

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.grantId()).isEqualTo(first.grantId());
        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::status,
                        ConversionEntitlementService.Allowance::grantedCharacters,
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters,
                        ConversionEntitlementService.Allowance::committedCharacters)
                .containsExactly(
                        ConversionEntitlementService.AllowanceStatus.AVAILABLE,
                        500_000L,
                        500_000L,
                        0L,
                        0L);
        assertThatThrownBy(() -> entitlementService.approveFreeGrant(
                        listenerId, "another-approval", "another-grant-operation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has");
        UUID anotherListener = listener("grant-replay-conflict");
        assertThatThrownBy(() -> entitlementService.approveFreeGrant(
                        anotherListener, "approval-case-22", "grant-operation-22"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency key");
    }

    @Test
    void settlementRestoresCharactersAndRetainsPotentiallyIncurredProviderCostIndependently() {
        UUID listenerId = listener("independent-settlement");
        UUID conversionId = UUID.randomUUID();
        entitlementService.approveFreeGrant(listenerId, "approval-independent", "grant-independent");

        ConversionEntitlementService.AdmissionDecision admission = entitlementService.authorizeSpeech(
                new ConversionEntitlementService.AdmissionRequest(
                        listenerId,
                        conversionId,
                        "openai",
                        "recipe-free-v1",
                        "rates-2026-08",
                        100_000,
                        1_000_000,
                        "reserve-independent"));
        ConversionEntitlementService.AdmissionDecision replay = entitlementService.authorizeSpeech(
                new ConversionEntitlementService.AdmissionRequest(
                        listenerId,
                        conversionId,
                        "openai",
                        "recipe-free-v1",
                        "rates-2026-08",
                        100_000,
                        1_000_000,
                        "reserve-independent"));

        assertThat(admission.authorized()).isTrue();
        assertThat(replay.reservationId()).isEqualTo(admission.reservationId());
        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters)
                .containsExactly(400_000L, 100_000L);
        assertThat(entitlementService.providerSpend("openai"))
                .extracting(
                        ConversionEntitlementService.ProviderSpend::reservedMicros,
                        ConversionEntitlementService.ProviderSpend::committedMicros)
                .containsExactly(1_000_000L, 0L);
        assertThatThrownBy(() -> entitlementService.settle(
                        new ConversionEntitlementService.SettlementRequest(
                                admission.reservationId(), 100_001, 0, "settle-too-many-characters")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");
        assertThatThrownBy(() -> entitlementService.settle(
                        new ConversionEntitlementService.SettlementRequest(
                                admission.reservationId(), 0, 1_000_001, "settle-too-much-cost")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");

        ConversionEntitlementService.Settlement settlement = entitlementService.settle(
                new ConversionEntitlementService.SettlementRequest(
                        admission.reservationId(), 0, 750_000, "settle-independent"));
        ConversionEntitlementService.Settlement settlementReplay = entitlementService.settle(
                new ConversionEntitlementService.SettlementRequest(
                        admission.reservationId(), 0, 750_000, "settle-independent"));

        assertThat(settlement.replayed()).isFalse();
        assertThat(settlementReplay.replayed()).isTrue();
        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters,
                        ConversionEntitlementService.Allowance::committedCharacters)
                .containsExactly(500_000L, 0L, 0L);
        assertThat(entitlementService.providerSpend("openai"))
                .extracting(
                        ConversionEntitlementService.ProviderSpend::reservedMicros,
                        ConversionEntitlementService.ProviderSpend::committedMicros)
                .containsExactly(0L, 750_000L);
    }

    @Test
    void correctionsAndExpiryAppendExactlyOnceToTheDerivedAllowance() {
        UUID listenerId = listener("correction-expiry");
        entitlementService.approveFreeGrant(listenerId, "approval-correction", "grant-correction");
        ConversionEntitlementService.AdmissionDecision usedCharacters = entitlementService.authorizeSpeech(
                request(listenerId, UUID.randomUUID(), "correction-provider", 20_000, 1, "correction-use"));
        settle(usedCharacters, 20_000, 0, "correction-use-settlement");

        ConversionEntitlementService.Correction correction = entitlementService.correctCharacters(
                new ConversionEntitlementService.CorrectionRequest(
                        listenerId, 20_000, "case-credit-22", "correct-22"));
        ConversionEntitlementService.Correction correctionReplay = entitlementService.correctCharacters(
                new ConversionEntitlementService.CorrectionRequest(
                        listenerId, 20_000, "case-credit-22", "correct-22"));

        assertThat(correction.replayed()).isFalse();
        assertThat(correctionReplay.replayed()).isTrue();
        assertThat(entitlementService.allowance(listenerId).availableCharacters()).isEqualTo(500_000);
        assertThatThrownBy(() -> entitlementService.correctCharacters(
                        new ConversionEntitlementService.CorrectionRequest(
                                listenerId, 1, "case-over-credit-22", "correct-over-22")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ceiling");

        ConversionEntitlementService.Expiry expiry = entitlementService.expireFreeGrant(
                listenerId, "policy-expiry-22", "expire-22");
        ConversionEntitlementService.Expiry expiryReplay = entitlementService.expireFreeGrant(
                listenerId, "policy-expiry-22", "expire-22");

        assertThat(expiry.expiredCharacters()).isEqualTo(500_000);
        assertThat(expiryReplay.replayed()).isTrue();
        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::status,
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::denialReason)
                .containsExactly(
                        ConversionEntitlementService.AllowanceStatus.EXPIRED,
                        0L,
                        "GRANT_EXPIRED");
    }

    @Test
    void currentLocalStateDeniesEveryAdmissionGateBeforeSpendIsReserved() {
        assertDenied(
                request(listener("no-grant"), UUID.randomUUID(), "no-grant-provider", 1, 1, "no-grant-op"),
                ConversionEntitlementService.AdmissionDenial.NO_GRANT);

        UUID bounded = listener("bounded");
        grant(bounded, "bounded");
        assertDenied(
                request(bounded, UUID.randomUUID(), "bounded-provider", 500_001, 1, "character-cap"),
                ConversionEntitlementService.AdmissionDenial.PER_CONVERSION_CHARACTER_LIMIT);
        assertDenied(
                request(bounded, UUID.randomUUID(), "bounded-provider", 1, 1_000_001, "cost-cap"),
                ConversionEntitlementService.AdmissionDenial.PER_CONVERSION_SPEND_LIMIT);

        UUID activeConversion = UUID.randomUUID();
        ConversionEntitlementService.AdmissionDecision active = entitlementService.authorizeSpeech(
                request(bounded, activeConversion, "bounded-provider", 100_000, 1_000_000, "active-one"));
        assertDenied(
                request(bounded, UUID.randomUUID(), "bounded-provider", 1, 1, "listener-concurrency"),
                ConversionEntitlementService.AdmissionDenial.LISTENER_CONCURRENCY_LIMIT);
        assertDenied(
                request(bounded, activeConversion, "bounded-provider", 1, 1, "same-conversion"),
                ConversionEntitlementService.AdmissionDenial.CONVERSION_ALREADY_RESERVED);
        settle(active, 0, 1_000_000, "active-one-settlement");

        ConversionEntitlementService.AdmissionDecision secondSpend = entitlementService.authorizeSpeech(
                request(bounded, UUID.randomUUID(), "bounded-provider", 1, 1_000_000, "listener-spend-two"));
        settle(secondSpend, 0, 1_000_000, "listener-spend-two-settlement");
        assertDenied(
                request(bounded, UUID.randomUUID(), "bounded-provider", 1, 1, "listener-spend-limit"),
                ConversionEntitlementService.AdmissionDenial.LISTENER_SPEND_LIMIT);

        UUID exhausted = listener("exhausted");
        grant(exhausted, "exhausted");
        ConversionEntitlementService.AdmissionDecision allCharacters = entitlementService.authorizeSpeech(
                request(exhausted, UUID.randomUUID(), "character-provider", 500_000, 1, "all-characters"));
        settle(allCharacters, 500_000, 0, "all-characters-settlement");
        assertDenied(
                request(exhausted, UUID.randomUUID(), "character-provider", 1, 1, "insufficient"),
                ConversionEntitlementService.AdmissionDenial.INSUFFICIENT_CHARACTERS);

        UUID expired = listener("expired");
        grant(expired, "expired");
        entitlementService.expireFreeGrant(expired, "expiry-evidence", "expiry-operation");
        assertDenied(
                request(expired, UUID.randomUUID(), "expiry-provider", 1, 1, "expired-admission"),
                ConversionEntitlementService.AdmissionDenial.GRANT_EXPIRED);

        ConversionEntitlementService.AdmissionDecision[] concurrent = new ConversionEntitlementService.AdmissionDecision[3];
        for (int index = 0; index < concurrent.length; index++) {
            UUID listenerId = listener("global-concurrency-" + index);
            grant(listenerId, "global-concurrency-" + index);
            concurrent[index] = entitlementService.authorizeSpeech(request(
                    listenerId,
                    UUID.randomUUID(),
                    "concurrency-provider-" + index,
                    1,
                    1,
                    "global-concurrency-op-" + index));
        }
        UUID fourth = listener("global-concurrency-fourth");
        grant(fourth, "global-concurrency-fourth");
        assertDenied(
                request(fourth, UUID.randomUUID(), "concurrency-provider-fourth", 1, 1, "global-concurrency"),
                ConversionEntitlementService.AdmissionDenial.GLOBAL_CONCURRENCY_LIMIT);
        for (int index = 0; index < concurrent.length; index++) {
            settle(concurrent[index], 0, 0, "global-concurrency-settle-" + index);
        }

        UUID providerThird = listener("provider-third");
        grant(providerThird, "provider-third");
        ConversionEntitlementService.AdmissionDecision thirdProviderSpend = entitlementService.authorizeSpeech(
                request(providerThird, UUID.randomUUID(), "bounded-provider", 1, 1_000_000, "provider-third"));
        settle(thirdProviderSpend, 0, 1_000_000, "provider-third-settlement");
        UUID providerDenied = listener("provider-denied");
        grant(providerDenied, "provider-denied");
        assertDenied(
                request(providerDenied, UUID.randomUUID(), "bounded-provider", 1, 1, "provider-limit"),
                ConversionEntitlementService.AdmissionDenial.PROVIDER_SPEND_LIMIT);

        for (int index = 0; index < 2; index++) {
            UUID listenerId = listener("global-spend-" + index);
            grant(listenerId, "global-spend-" + index);
            ConversionEntitlementService.AdmissionDecision decision = entitlementService.authorizeSpeech(request(
                    listenerId,
                    UUID.randomUUID(),
                    "global-provider",
                    1,
                    1_000_000,
                    "global-spend-op-" + index));
            settle(decision, 0, 1_000_000, "global-spend-settle-" + index);
        }
        UUID globalDenied = listener("global-denied");
        grant(globalDenied, "global-denied");
        assertDenied(
                request(globalDenied, UUID.randomUUID(), "new-provider", 1, 1, "global-limit"),
                ConversionEntitlementService.AdmissionDenial.GLOBAL_SPEND_LIMIT);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM entitlement_audit_event WHERE decision = 'DENIED'",
                        Long.class))
                .isGreaterThanOrEqualTo(10);
    }

    @Test
    void persistedEntitlementEvidenceCannotBeUpdated() {
        UUID listenerId = listener("immutable-update");
        entitlementService.approveFreeGrant(listenerId, "immutable-update-approval", "immutable-update-grant");

        Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE character_entitlement_ledger_entry SET available_delta = 0 WHERE listener_id = ?",
                        listenerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void persistedEntitlementEvidenceCannotBeDeleted() {
        UUID listenerId = listener("immutable-delete");
        entitlementService.approveFreeGrant(listenerId, "immutable-delete-approval", "immutable-delete-grant");

        Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM entitlement_audit_event WHERE listener_id = ?",
                        listenerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private void grant(UUID listenerId, String key) {
        entitlementService.approveFreeGrant(listenerId, "approval-" + key, "grant-" + key);
    }

    private ConversionEntitlementService.AdmissionRequest request(
            UUID listenerId,
            UUID conversionId,
            String provider,
            long characters,
            long costMicros,
            String operation) {
        return new ConversionEntitlementService.AdmissionRequest(
                listenerId,
                conversionId,
                provider,
                "recipe-free-v1",
                "rates-2026-08",
                characters,
                costMicros,
                operation);
    }

    private void assertDenied(
            ConversionEntitlementService.AdmissionRequest request,
            ConversionEntitlementService.AdmissionDenial denial) {
        ConversionEntitlementService.AdmissionDecision decision = entitlementService.authorizeSpeech(request);
        ConversionEntitlementService.AdmissionDecision replay = entitlementService.authorizeSpeech(request);
        assertThat(decision.authorized()).isFalse();
        assertThat(decision.denial()).isEqualTo(denial);
        assertThat(replay.denial()).isEqualTo(denial);
        assertThat(replay.replayed()).isTrue();
    }

    private void settle(
            ConversionEntitlementService.AdmissionDecision admission,
            long characters,
            long costMicros,
            String operation) {
        entitlementService.settle(new ConversionEntitlementService.SettlementRequest(
                admission.reservationId(), characters, costMicros, operation));
    }

    private UUID listener(String subject) {
        return listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        subject + "-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Entitlement Listener"))
                .listenerId();
    }
}
