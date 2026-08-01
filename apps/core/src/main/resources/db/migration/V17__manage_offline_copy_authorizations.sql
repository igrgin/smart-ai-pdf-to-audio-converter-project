CREATE SCHEMA offline_access;

CREATE TABLE offline_access.authorization_generation (
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    audiobook_id UUID NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (listener_id, audiobook_id),
    FOREIGN KEY (audiobook_id, listener_id)
        REFERENCES library.private_audiobook(audiobook_id, listener_id)
);

CREATE TABLE offline_access.authorization_operation (
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    operation_key VARCHAR(200) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    installation_id UUID NOT NULL,
    audiobook_id UUID NOT NULL,
    asset_version_id UUID NOT NULL,
    authorization_generation BIGINT NOT NULL CHECK (authorization_generation > 0),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    signed_payload TEXT NOT NULL,
    signature TEXT NOT NULL,
    PRIMARY KEY (listener_id, operation_key),
    FOREIGN KEY (audiobook_id, listener_id)
        REFERENCES library.private_audiobook(audiobook_id, listener_id),
    FOREIGN KEY (asset_version_id, audiobook_id, listener_id)
        REFERENCES library.audiobook_asset_version(asset_version_id, audiobook_id, listener_id),
    CHECK (expires_at > issued_at),
    CHECK (expires_at <= issued_at + INTERVAL '30 days')
);

CREATE INDEX authorization_operation_resource_idx
    ON offline_access.authorization_operation (listener_id, audiobook_id, asset_version_id, expires_at);

ALTER TABLE offline_access.authorization_generation ENABLE ROW LEVEL SECURITY;
ALTER TABLE offline_access.authorization_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY authorization_generation_listener_policy ON offline_access.authorization_generation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY authorization_operation_listener_policy ON offline_access.authorization_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

CREATE FUNCTION offline_access.advance_audiobook_authorization_generation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (OLD.availability = 'AVAILABLE' AND NEW.availability <> 'AVAILABLE')
        OR OLD.current_asset_version_id IS DISTINCT FROM NEW.current_asset_version_id THEN
        UPDATE offline_access.authorization_generation
        SET generation = generation + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE listener_id = NEW.listener_id
          AND audiobook_id = NEW.audiobook_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER private_audiobook_offline_access_revocation
    AFTER UPDATE OF availability, current_asset_version_id ON library.private_audiobook
    FOR EACH ROW
    EXECUTE FUNCTION offline_access.advance_audiobook_authorization_generation();

CREATE FUNCTION offline_access.advance_listener_authorization_generations()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.access_state = 'ACTIVE' AND NEW.access_state <> 'ACTIVE' THEN
        UPDATE offline_access.authorization_generation
        SET generation = generation + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE listener_id = NEW.listener_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER listener_offline_access_revocation
    AFTER UPDATE OF access_state ON listener_identity
    FOR EACH ROW
    EXECUTE FUNCTION offline_access.advance_listener_authorization_generations();
