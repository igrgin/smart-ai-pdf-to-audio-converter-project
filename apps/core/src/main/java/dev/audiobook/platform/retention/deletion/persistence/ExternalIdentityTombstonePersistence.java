package dev.audiobook.platform.retention.deletion.persistence;

public interface ExternalIdentityTombstonePersistence {

    boolean exists(String identityDigest);
}
