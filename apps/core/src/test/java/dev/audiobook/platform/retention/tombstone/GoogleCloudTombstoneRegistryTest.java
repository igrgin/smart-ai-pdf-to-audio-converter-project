package dev.audiobook.platform.retention.tombstone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import dev.audiobook.platform.retention.RetentionProperties;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class GoogleCloudTombstoneRegistryTest {

    private final Storage storage = mock(Storage.class);
    private final GoogleCloudTombstoneRegistry registry =
            new GoogleCloudTombstoneRegistry(storage, properties());

    @Test
    void appendsAnImmutableContentFreeObjectAndAcceptsAnIdenticalReplay() {
        var tombstone = tombstone();
        when(storage.create(
                        any(BlobInfo.class),
                        any(byte[].class),
                        any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(412, "exists"));
        Blob existing = mock(Blob.class);
        when(storage.get("retention-test-bucket", "deletion-tombstones/" + tombstone.tombstoneId() + ".json"))
                .thenReturn(existing);
        when(existing.getContent()).thenReturn(json(tombstone));

        registry.append(tombstone);

        verify(storage)
                .create(any(BlobInfo.class), eq(json(tombstone)), any(Storage.BlobTargetOption.class));
    }

    @Test
    void rejectsConflictingReplayAndOrdersRegistryEntries() {
        var first = tombstone();
        var second =
                new TombstoneRegistry.TombstoneRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ACCOUNT",
                        "b".repeat(64),
                        null,
                        Instant.parse("2026-08-03T00:00:00Z"));
        Blob firstBlob = mock(Blob.class);
        Blob secondBlob = mock(Blob.class);
        when(firstBlob.getContent()).thenReturn(json(first));
        when(secondBlob.getContent()).thenReturn(json(second));
        @SuppressWarnings("unchecked")
        Page<Blob> page = mock(Page.class);
        when(storage.list(eq("retention-test-bucket"), any(Storage.BlobListOption[].class)))
                .thenReturn(page);
        when(page.iterateAll()).thenReturn(List.of(secondBlob, firstBlob));

        assertThat(registry.entries()).containsExactly(first, second);

        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
                .thenThrow(new StorageException(412, "exists"));
        when(storage.get("retention-test-bucket", "deletion-tombstones/" + first.tombstoneId() + ".json"))
                .thenReturn(firstBlob);
        when(firstBlob.getContent()).thenReturn(new byte[] {1});
        assertThatThrownBy(() -> registry.append(first))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    private static TombstoneRegistry.TombstoneRecord tombstone() {
        return new TombstoneRegistry.TombstoneRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "AUDIOBOOK",
                "a".repeat(64),
                "c".repeat(64),
                Instant.parse("2026-08-02T00:00:00Z"));
    }

    private static byte[] json(TombstoneRegistry.TombstoneRecord tombstone) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsBytes(tombstone);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
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
