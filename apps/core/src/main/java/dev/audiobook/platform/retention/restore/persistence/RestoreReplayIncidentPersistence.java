package dev.audiobook.platform.retention.restore.persistence;

public interface RestoreReplayIncidentPersistence {

    void resolveFailure();

    void recordFailure();
}
