package dev.audiobook.platform.retention.restore.persistence;

import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import java.util.List;
import java.util.UUID;

public interface TombstoneReplayPersistence {

    void importTombstone(TombstoneRegistry.TombstoneRecord tombstone);

    List<Tombstone> tombstones();

    List<ListenerReference> listeners();

    List<AudiobookReference> audiobooks();

    void denyAudiobook(UUID listenerId, UUID audiobookId);

    void denyAccount(UUID listenerId);

    boolean hasIncompleteRequest(String subjectDigest, String resourceDigest);

    void reissueAudiobook(Tombstone tombstone, UUID listenerId, UUID audiobookId);

    void reissueAccount(Tombstone tombstone, UUID listenerId);

    record Tombstone(
            UUID tombstoneId, String scope, String subjectDigest, String resourceDigest) {}

    record ListenerReference(UUID listenerId, String accessState) {}

    record AudiobookReference(UUID listenerId, UUID audiobookId, String availability) {}
}
