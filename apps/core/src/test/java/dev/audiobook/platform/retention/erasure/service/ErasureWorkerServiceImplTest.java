package dev.audiobook.platform.retention.erasure.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.admission.QuarantineObjectStore;
import dev.audiobook.platform.generation.assets.AudiobookAssetStore;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.retention.RetentionProperties;
import dev.audiobook.platform.retention.erasure.persistence.ErasureWorkerPersistence;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class ErasureWorkerServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private final ErasureWorkerPersistence persistence = mock(ErasureWorkerPersistence.class);
    private final AudiobookAssetStore audiobookAssets = mock(AudiobookAssetStore.class);
    private final ErasureWorkerServiceImpl service =
            new ErasureWorkerServiceImpl(
                    persistence,
                    audiobookAssets,
                    mock(NarrationPlanAssetStore.class),
                    mock(NarrationReviewAssetStore.class),
                    mock(QuarantineObjectStore.class),
                    properties(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storageFailureIsRetryableAndTheFinalAttemptCreatesAnIncident() throws Exception {
        UUID requestId = UUID.randomUUID();
        var obligation = obligation(requestId, "AUDIO_FINAL", "audiobooks/private.mp3", 4);
        when(persistence.claimPending()).thenReturn(List.of(obligation));
        when(persistence.claimEligibleRelational()).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IOException("unavailable"))
                .when(audiobookAssets)
                .deleteFinal("audiobooks/private.mp3");

        assertThat(service.erasePending()).isZero();

        verify(persistence).markErasing(obligation);
        verify(persistence).fail(obligation, "ASSET_STORE_UNAVAILABLE");
        verify(persistence).failRequest(requestId, "ASSET_STORE_UNAVAILABLE");
        verify(persistence)
                .createIncident(requestId, "ERASURE_ATTEMPTS_EXHAUSTED", NOW, NOW);
    }

    @Test
    void providerProofCompletesBeforeRelationalPrivateDataIsRemoved() {
        UUID requestId = UUID.randomUUID();
        var provider = obligation(requestId, "PROVIDER_EVIDENCE", "provider-operation", 0);
        var relational =
                obligation(requestId, "RELATIONAL_PRIVATE_DATA", "ACCOUNT\n" + UUID.randomUUID(), 0);
        when(persistence.claimPending()).thenReturn(List.of(provider));
        when(persistence.claimEligibleRelational()).thenReturn(List.of(relational));
        when(persistence.hasQualifiedProviderEvidence("provider-operation")).thenReturn(true);

        assertThat(service.erasePending()).isEqualTo(2);

        InOrder order = inOrder(persistence);
        order.verify(persistence).complete(provider, "PROVIDER_NON_RETENTION_EVIDENCED");
        order.verify(persistence).eraseRelational(requestId, relational.locator());
        order.verify(persistence).complete(relational, "PRIVATE_RELATIONAL_DATA_DELETED");
    }

    private static ErasureWorkerPersistence.Obligation obligation(
            UUID requestId, String kind, String locator, int attempts) {
        return new ErasureWorkerPersistence.Obligation(
                UUID.randomUUID(), requestId, kind, locator, attempts);
    }

    private static RetentionProperties properties() {
        return new RetentionProperties(
                "retention-test-key-with-32-characters",
                Path.of("retention-test"),
                "retention-test-bucket",
                Duration.ofHours(24),
                Duration.ofDays(23),
                Duration.ofDays(30),
                Duration.ofDays(90),
                Duration.ofDays(365),
                100,
                5,
                true);
    }
}
