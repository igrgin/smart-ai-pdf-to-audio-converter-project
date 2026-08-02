package dev.audiobook.platform.retention.reconciliation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ErasureDeadlinePolicy {

    private ErasureDeadlinePolicy() {}

    public static List<Incident> incidents(Progress progress, Instant now) {
        List<Incident> incidents = new ArrayList<>();
        if (now.isAfter(progress.quickDueAt())
                && progress.liveTotal() > 0
                && progress.liveCompleted() * 100L < progress.liveTotal() * 99L) {
            incidents.add(
                    new Incident("LIVE_ERASURE_24H_TARGET_MISSED", progress.quickDueAt()));
        }
        if (now.isAfter(progress.liveDueAt())
                && progress.liveCompleted() < progress.liveTotal()) {
            incidents.add(
                    new Incident("LIVE_ERASURE_DAY23_DEADLINE_MISSED", progress.liveDueAt()));
        }
        if (now.isAfter(progress.providerDueAt()) && progress.providerIncomplete() > 0) {
            incidents.add(
                    new Incident(
                            "PROVIDER_EVIDENCE_30D_DEADLINE_MISSED",
                            progress.providerDueAt()));
        }
        if (progress.exhausted() > 0) {
            incidents.add(new Incident("ERASURE_ATTEMPTS_EXHAUSTED", now));
        }
        return List.copyOf(incidents);
    }

    public record Progress(
            UUID requestId,
            Instant quickDueAt,
            Instant liveDueAt,
            Instant providerDueAt,
            int liveTotal,
            int liveCompleted,
            int providerIncomplete,
            int exhausted) {}

    public record Incident(String code, Instant deadline) {}
}
