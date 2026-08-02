package dev.audiobook.platform.retention.tombstone;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TombstoneRegistry {

    void append(TombstoneRecord tombstone);

    List<TombstoneRecord> entries();

    record TombstoneRecord(
            UUID tombstoneId,
            UUID requestId,
            String scope,
            String subjectDigest,
            String resourceDigest,
            Instant createdAt) {}
}
