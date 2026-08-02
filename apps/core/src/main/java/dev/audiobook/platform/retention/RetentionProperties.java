package dev.audiobook.platform.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.nio.file.Path;

@ConfigurationProperties("platform.retention")
public record RetentionProperties(
        String tombstoneKey,
        Path tombstoneRegistryPath,
        String tombstoneRegistryBucket,
        Duration quickErasureTarget,
        Duration liveErasureDeadline,
        Duration providerEvidenceDeadline,
        Duration backupExpiry,
        Duration evidenceRetention,
        int workerBatchSize,
        int maximumAttempts,
        boolean restoreReplayEnabled) {

    public RetentionProperties {
        if (tombstoneKey == null || tombstoneKey.length() < 32) {
            throw new IllegalArgumentException("Retention tombstone key must have at least 32 characters");
        }
        if (tombstoneRegistryPath == null) {
            throw new IllegalArgumentException("Tombstone registry path is required");
        }
        if (tombstoneRegistryBucket == null || tombstoneRegistryBucket.isBlank()) {
            throw new IllegalArgumentException("Tombstone registry bucket is required");
        }
        requireBoundedPositive(quickErasureTarget, Duration.ofHours(24), "Quick erasure target");
        requireBoundedPositive(liveErasureDeadline, Duration.ofDays(23), "Live erasure deadline");
        requireBoundedPositive(
                providerEvidenceDeadline, Duration.ofDays(30), "Provider evidence deadline");
        requireBoundedPositive(backupExpiry, Duration.ofDays(90), "Backup expiry");
        if (evidenceRetention == null || evidenceRetention.compareTo(backupExpiry) < 0) {
            throw new IllegalArgumentException("Evidence retention must cover backup expiry");
        }
        if (workerBatchSize < 1 || workerBatchSize > 1_000) {
            throw new IllegalArgumentException("Erasure worker batch size must be between 1 and 1000");
        }
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException("Erasure maximum attempts must be between 1 and 100");
        }
    }

    private static void requireBoundedPositive(
            Duration value, Duration maximum, String description) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(description + " must be positive and no greater than " + maximum);
        }
    }
}
