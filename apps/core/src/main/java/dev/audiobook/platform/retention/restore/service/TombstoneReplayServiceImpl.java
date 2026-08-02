package dev.audiobook.platform.retention.restore.service;

import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.restore.persistence.TombstoneReplayPersistence;
import dev.audiobook.platform.retention.restore.persistence.TombstoneReplayPersistence.AudiobookReference;
import dev.audiobook.platform.retention.restore.persistence.TombstoneReplayPersistence.ListenerReference;
import dev.audiobook.platform.retention.restore.persistence.TombstoneReplayPersistence.Tombstone;
import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TombstoneReplayServiceImpl implements TombstoneReplayService {

    private final TombstoneReplayPersistence persistence;
    private final TombstoneRegistry tombstoneRegistry;
    private final RetentionDigest retentionDigest;

    @Override
    @Transactional
    public ReplayReport replay() {
        for (var tombstone : tombstoneRegistry.entries()) {
            if (!tombstone.scope().equals("ACCOUNT") && !tombstone.scope().equals("AUDIOBOOK")) {
                throw new IllegalStateException("Tombstone registry scope is invalid");
            }
            persistence.importTombstone(tombstone);
        }
        List<Tombstone> tombstones = persistence.tombstones();
        List<ListenerReference> listeners = persistence.listeners();
        List<AudiobookReference> audiobooks = persistence.audiobooks();
        int denied = 0;
        int reissued = 0;
        for (Tombstone tombstone : tombstones) {
            if (tombstone.scope().equals("ACCOUNT")) {
                for (ListenerReference listener : listeners) {
                    if (matchesListener(tombstone, listener.listenerId())
                            && accountNeedsReplay(listener, audiobooks)) {
                        persistence.denyAccount(listener.listenerId());
                        denied++;
                        if (!persistence.hasIncompleteRequest(tombstone.subjectDigest(), null)) {
                            persistence.reissueAccount(tombstone, listener.listenerId());
                            reissued++;
                        }
                    }
                }
            } else {
                for (AudiobookReference audiobook : audiobooks) {
                    if (matchesAudiobook(tombstone, audiobook) && needsDenial(audiobook)) {
                        persistence.denyAudiobook(
                                audiobook.listenerId(), audiobook.audiobookId());
                        denied++;
                        if (!persistence.hasIncompleteRequest(
                                tombstone.subjectDigest(), tombstone.resourceDigest())) {
                            persistence.reissueAudiobook(
                                    tombstone,
                                    audiobook.listenerId(),
                                    audiobook.audiobookId());
                            reissued++;
                        }
                    }
                }
            }
        }
        return new ReplayReport(tombstones.size(), denied, reissued);
    }

    private boolean matchesListener(Tombstone tombstone, UUID listenerId) {
        return tombstone.subjectDigest()
                .equals(retentionDigest.digest("listener", listenerId.toString()));
    }

    private boolean matchesAudiobook(Tombstone tombstone, AudiobookReference audiobook) {
        return matchesListener(tombstone, audiobook.listenerId())
                && tombstone.resourceDigest()
                        .equals(
                                retentionDigest.digest(
                                        "audiobook", audiobook.audiobookId().toString()));
    }

    private static boolean accountNeedsReplay(
            ListenerReference listener, List<AudiobookReference> audiobooks) {
        if (!listener.accessState().equals("DELETED")) {
            return true;
        }
        return audiobooks.stream()
                .anyMatch(
                        audiobook ->
                                audiobook.listenerId().equals(listener.listenerId())
                                        && needsDenial(audiobook));
    }

    private static boolean needsDenial(AudiobookReference audiobook) {
        return !audiobook.availability().equals("DELETING")
                && !audiobook.availability().equals("ERASED");
    }
}
