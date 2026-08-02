package dev.audiobook.platform.retention.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class ErasureDeadlinePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");

    @Test
    void reportsEveryIndependentMissedBoundaryAndExhaustedRetry() {
        var progress =
                new ErasureDeadlinePolicy.Progress(
                        UUID.randomUUID(),
                        NOW.minusSeconds(1),
                        NOW.minusSeconds(2),
                        NOW.minusSeconds(3),
                        100,
                        98,
                        1,
                        1);

        assertThat(ErasureDeadlinePolicy.incidents(progress, NOW))
                .extracting(ErasureDeadlinePolicy.Incident::code)
                .containsExactly(
                        "LIVE_ERASURE_24H_TARGET_MISSED",
                        "LIVE_ERASURE_DAY23_DEADLINE_MISSED",
                        "PROVIDER_EVIDENCE_30D_DEADLINE_MISSED",
                        "ERASURE_ATTEMPTS_EXHAUSTED");
    }

    @Test
    void considersNinetyNinePercentOnTimeAndProviderProofIndependent() {
        var progress =
                new ErasureDeadlinePolicy.Progress(
                        UUID.randomUUID(),
                        NOW.minusSeconds(1),
                        NOW.plusSeconds(1),
                        NOW.minusSeconds(1),
                        100,
                        99,
                        1,
                        0);

        assertThat(ErasureDeadlinePolicy.incidents(progress, NOW))
                .extracting(ErasureDeadlinePolicy.Incident::code)
                .containsExactly("PROVIDER_EVIDENCE_30D_DEADLINE_MISSED");
    }

    @Test
    void doesNotReportDeadlinesBeforeTheyExpire() {
        var progress =
                new ErasureDeadlinePolicy.Progress(
                        UUID.randomUUID(),
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(2),
                        NOW.plusSeconds(3),
                        1,
                        0,
                        1,
                        0);

        assertThat(ErasureDeadlinePolicy.incidents(progress, NOW)).isEmpty();
    }
}
