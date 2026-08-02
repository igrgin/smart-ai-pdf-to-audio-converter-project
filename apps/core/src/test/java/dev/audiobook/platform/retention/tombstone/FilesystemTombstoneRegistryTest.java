package dev.audiobook.platform.retention.tombstone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.audiobook.platform.retention.RetentionProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class FilesystemTombstoneRegistryTest {

    @TempDir Path root;

    @Test
    void appendIsImmutableIdempotentAndReplayable() throws Exception {
        var registry =
                new FilesystemTombstoneRegistry(properties());
        var tombstone =
                new TombstoneRegistry.TombstoneRecord(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "AUDIOBOOK",
                        "a".repeat(64),
                        "b".repeat(64),
                        Instant.parse("2026-08-02T12:00:00Z"));

        registry.append(tombstone);

        assertThatCode(() -> registry.append(tombstone)).doesNotThrowAnyException();
        assertThat(registry.entries()).containsExactly(tombstone);
    }

    private RetentionProperties properties() {
        return new RetentionProperties(
                "retention-test-key-with-32-characters",
                root,
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
