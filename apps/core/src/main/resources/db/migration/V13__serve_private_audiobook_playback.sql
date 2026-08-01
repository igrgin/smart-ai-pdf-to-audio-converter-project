ALTER TABLE listener_identity
    ADD COLUMN access_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    CHECK (access_state IN ('ACTIVE', 'BANNED'));

CREATE TABLE library.playback_position (
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    audiobook_id UUID NOT NULL,
    asset_version_id UUID NOT NULL,
    position_ms BIGINT NOT NULL CHECK (position_ms >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (listener_id, audiobook_id),
    FOREIGN KEY (audiobook_id, listener_id)
        REFERENCES library.private_audiobook(audiobook_id, listener_id),
    FOREIGN KEY (asset_version_id, audiobook_id, listener_id)
        REFERENCES library.audiobook_asset_version(asset_version_id, audiobook_id, listener_id)
);

CREATE TABLE library.playback_position_operation (
    operation_key VARCHAR(200) NOT NULL,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    audiobook_id UUID NOT NULL,
    asset_version_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    position_ms BIGINT NOT NULL CHECK (position_ms >= 0),
    result_version BIGINT NOT NULL CHECK (result_version > 0),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (listener_id, operation_key),
    FOREIGN KEY (audiobook_id, listener_id)
        REFERENCES library.private_audiobook(audiobook_id, listener_id),
    FOREIGN KEY (asset_version_id, audiobook_id, listener_id)
        REFERENCES library.audiobook_asset_version(asset_version_id, audiobook_id, listener_id)
);

ALTER TABLE library.playback_position ENABLE ROW LEVEL SECURITY;
ALTER TABLE library.playback_position_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY playback_position_listener_policy ON library.playback_position
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY playback_position_operation_listener_policy ON library.playback_position_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
