package dev.audiobook.platform.retention.restore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.RetentionProperties;
import dev.audiobook.platform.retention.restore.persistence.TombstoneReplayPersistence;
import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class TombstoneReplayServiceImplTest {

    private final TombstoneReplayPersistence persistence = mock(TombstoneReplayPersistence.class);
    private final TombstoneRegistry registry = mock(TombstoneRegistry.class);
    private final RetentionDigest digest = new RetentionDigest(properties());
    private final TombstoneReplayServiceImpl service =
            new TombstoneReplayServiceImpl(persistence, registry, digest);

    @Test
    void matchesOpaqueTombstonesAndReissuesOnlyWhenNoRequestIsActive() {
        UUID listenerId = UUID.randomUUID();
        UUID audiobookId = UUID.randomUUID();
        var tombstone =
                new TombstoneReplayPersistence.Tombstone(
                        UUID.randomUUID(),
                        "AUDIOBOOK",
                        digest.digest("listener", listenerId.toString()),
                        digest.digest("audiobook", audiobookId.toString()));
        when(persistence.tombstones()).thenReturn(List.of(tombstone));
        when(persistence.listeners()).thenReturn(List.of());
        when(persistence.audiobooks())
                .thenReturn(
                        List.of(
                                new TombstoneReplayPersistence.AudiobookReference(
                                        listenerId, audiobookId, "AVAILABLE")));
        when(persistence.hasIncompleteRequest(
                        tombstone.subjectDigest(), tombstone.resourceDigest()))
                .thenReturn(false);

        assertThat(service.replay()).isEqualTo(new TombstoneReplayService.ReplayReport(1, 1, 1));

        verify(persistence).denyAudiobook(listenerId, audiobookId);
        verify(persistence).reissueAudiobook(tombstone, listenerId, audiobookId);
    }

    @Test
    void alreadyDeniedReferencesAreNotTouched() {
        UUID listenerId = UUID.randomUUID();
        UUID audiobookId = UUID.randomUUID();
        var tombstone =
                new TombstoneReplayPersistence.Tombstone(
                        UUID.randomUUID(),
                        "AUDIOBOOK",
                        digest.digest("listener", listenerId.toString()),
                        digest.digest("audiobook", audiobookId.toString()));
        when(persistence.tombstones()).thenReturn(List.of(tombstone));
        when(persistence.listeners()).thenReturn(List.of());
        when(persistence.audiobooks())
                .thenReturn(
                        List.of(
                                new TombstoneReplayPersistence.AudiobookReference(
                                        listenerId, audiobookId, "DELETING")));

        assertThat(service.replay()).isEqualTo(new TombstoneReplayService.ReplayReport(1, 0, 0));

        verify(persistence, never()).denyAudiobook(listenerId, audiobookId);
    }

    @Test
    void invalidExternalRegistryScopeFailsClosedBeforeImport() {
        var invalid =
                new TombstoneRegistry.TombstoneRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "UNKNOWN",
                        "a".repeat(64),
                        null,
                        Instant.parse("2026-08-02T00:00:00Z"));
        when(registry.entries()).thenReturn(List.of(invalid));

        assertThatThrownBy(service::replay)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope");

        verify(persistence, never()).importTombstone(invalid);
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
