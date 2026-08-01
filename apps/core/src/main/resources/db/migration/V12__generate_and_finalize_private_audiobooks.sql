ALTER TABLE workflow.audiobook_conversion
    DROP CONSTRAINT audiobook_conversion_state_check;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_state_check
    CHECK (state IN (
        'PREPARING', 'AWAITING_REVIEW', 'GENERATING', 'FINALIZING',
        'FINALIZED', 'PAUSED', 'FAILED'
    ));

CREATE SCHEMA IF NOT EXISTS generation;
CREATE SCHEMA IF NOT EXISTS library;

CREATE TABLE generation.segment_manifest (
    manifest_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    recipe_id UUID NOT NULL REFERENCES narration.generation_recipe(recipe_id),
    review_decision_id UUID NOT NULL REFERENCES narration.narration_review_decision(decision_id),
    recipe_digest CHAR(64) NOT NULL,
    narration_plan_digest CHAR(64) NOT NULL,
    segmentation_policy_version VARCHAR(100) NOT NULL,
    manifest_digest CHAR(64) NOT NULL,
    segment_count INTEGER NOT NULL CHECK (segment_count > 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id),
    UNIQUE (manifest_id, listener_id, conversion_id)
);

CREATE TABLE generation.audiobook_chapter_plan (
    manifest_id UUID NOT NULL REFERENCES generation.segment_manifest(manifest_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    chapter_ordinal INTEGER NOT NULL CHECK (chapter_ordinal >= 0),
    display_title VARCHAR(300) NOT NULL,
    PRIMARY KEY (manifest_id, chapter_ordinal),
    FOREIGN KEY (manifest_id, listener_id, conversion_id)
        REFERENCES generation.segment_manifest(manifest_id, listener_id, conversion_id)
);

CREATE TABLE generation.speech_segment (
    segment_id CHAR(64) PRIMARY KEY,
    manifest_id UUID NOT NULL REFERENCES generation.segment_manifest(manifest_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    chapter_ordinal INTEGER NOT NULL CHECK (chapter_ordinal >= 0),
    segment_ordinal INTEGER NOT NULL CHECK (segment_ordinal >= 0),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    spoken_text_ref VARCHAR(300) NOT NULL UNIQUE,
    spoken_text_sha256 CHAR(64) NOT NULL,
    character_count INTEGER NOT NULL CHECK (character_count > 0),
    boundary_kind VARCHAR(32) NOT NULL CHECK (boundary_kind IN (
        'LIMIT_CONTINUATION', 'PARAGRAPH', 'STRUCTURAL_SECTION', 'CHAPTER'
    )),
    next_attempt_number INTEGER NOT NULL DEFAULT 1 CHECK (next_attempt_number > 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (manifest_id, listener_id, conversion_id)
        REFERENCES generation.segment_manifest(manifest_id, listener_id, conversion_id),
    FOREIGN KEY (manifest_id, chapter_ordinal)
        REFERENCES generation.audiobook_chapter_plan(manifest_id, chapter_ordinal),
    UNIQUE (manifest_id, chapter_ordinal, segment_ordinal)
);

CREATE TABLE generation.speech_attempt (
    attempt_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    segment_id CHAR(64) NOT NULL REFERENCES generation.speech_segment(segment_id),
    operation_key VARCHAR(200) NOT NULL REFERENCES generation.speech_segment(operation_key),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'CALLING_PROVIDER', 'RECEIVED', 'ACCEPTED', 'DUPLICATE', 'FAILED'
    )),
    provider_request_id VARCHAR(200),
    actual_model VARCHAR(160),
    actual_region VARCHAR(80),
    actual_voice VARCHAR(100),
    received_sha256 CHAR(64),
    decoded_sha256 CHAR(64),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id),
    UNIQUE (operation_key, attempt_number)
);

CREATE TABLE generation.accepted_segment (
    operation_key VARCHAR(200) PRIMARY KEY REFERENCES generation.speech_segment(operation_key),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    segment_id CHAR(64) NOT NULL UNIQUE REFERENCES generation.speech_segment(segment_id),
    attempt_id UUID NOT NULL UNIQUE REFERENCES generation.speech_attempt(attempt_id),
    recipe_digest CHAR(64) NOT NULL,
    pcm_object_key VARCHAR(300) NOT NULL UNIQUE,
    pcm_sha256 CHAR(64) NOT NULL,
    byte_length BIGINT NOT NULL CHECK (byte_length > 0 AND byte_length % 2 = 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    accepted_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE generation.packaged_audiobook_result (
    conversion_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    manifest_digest CHAR(64) NOT NULL UNIQUE,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE library.private_audiobook (
    audiobook_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    availability VARCHAR(32) NOT NULL CHECK (availability IN (
        'AVAILABLE', 'RIGHTS_QUARANTINED', 'TECHNICALLY_UNAVAILABLE', 'DELETING', 'ERASED'
    )),
    current_asset_version_id UUID,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id),
    UNIQUE (audiobook_id, listener_id)
);

CREATE TABLE library.audiobook_asset_version (
    asset_version_id UUID PRIMARY KEY,
    audiobook_id UUID NOT NULL REFERENCES library.private_audiobook(audiobook_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    generation_recipe_id UUID NOT NULL REFERENCES narration.generation_recipe(recipe_id),
    recipe_digest CHAR(64) NOT NULL,
    manifest_object_key VARCHAR(300) NOT NULL UNIQUE,
    manifest_digest CHAR(64) NOT NULL UNIQUE,
    packaging_profile_version VARCHAR(100) NOT NULL,
    total_duration_ms BIGINT NOT NULL CHECK (total_duration_ms > 0),
    total_bytes BIGINT NOT NULL CHECK (total_bytes > 0),
    integrated_loudness_lufs NUMERIC(8, 3) NOT NULL,
    true_peak_dbtp NUMERIC(8, 3) NOT NULL,
    applied_gain_db NUMERIC(8, 3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (asset_version_id, audiobook_id, listener_id)
);

ALTER TABLE library.private_audiobook
    ADD CONSTRAINT private_audiobook_current_asset_fk
    FOREIGN KEY (current_asset_version_id, audiobook_id, listener_id)
    REFERENCES library.audiobook_asset_version(asset_version_id, audiobook_id, listener_id);

CREATE TABLE library.audiobook_chapter (
    chapter_id UUID PRIMARY KEY,
    asset_version_id UUID NOT NULL REFERENCES library.audiobook_asset_version(asset_version_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    chapter_ordinal INTEGER NOT NULL CHECK (chapter_ordinal >= 0),
    display_title VARCHAR(300) NOT NULL,
    start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    UNIQUE (asset_version_id, chapter_ordinal),
    UNIQUE (chapter_id, asset_version_id, listener_id)
);

CREATE TABLE library.final_asset_part (
    part_id UUID PRIMARY KEY,
    chapter_id UUID NOT NULL REFERENCES library.audiobook_chapter(chapter_id),
    asset_version_id UUID NOT NULL REFERENCES library.audiobook_asset_version(asset_version_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    chapter_ordinal INTEGER NOT NULL CHECK (chapter_ordinal >= 0),
    part_ordinal INTEGER NOT NULL CHECK (part_ordinal >= 0),
    object_key VARCHAR(300) NOT NULL UNIQUE,
    mime_type VARCHAR(80) NOT NULL CHECK (mime_type = 'audio/mpeg'),
    byte_length BIGINT NOT NULL CHECK (byte_length > 0),
    duration_ms BIGINT NOT NULL CHECK (duration_ms > 0),
    sha256 CHAR(64) NOT NULL,
    UNIQUE (asset_version_id, chapter_ordinal, part_ordinal),
    FOREIGN KEY (chapter_id, asset_version_id, listener_id)
        REFERENCES library.audiobook_chapter(chapter_id, asset_version_id, listener_id)
);

CREATE TABLE generation.working_asset_erasure_obligation (
    obligation_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    begins_at TIMESTAMPTZ NOT NULL,
    erase_by TIMESTAMPTZ NOT NULL CHECK (erase_by <= begins_at + INTERVAL '30 days'),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'ERASING', 'ERASED')),
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE FUNCTION generation.reject_immutable_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Finalized generation records are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION generation.reject_speech_segment_drift() RETURNS trigger AS $$
BEGIN
    IF NEW.segment_id = OLD.segment_id
       AND NEW.manifest_id = OLD.manifest_id
       AND NEW.listener_id = OLD.listener_id
       AND NEW.conversion_id = OLD.conversion_id
       AND NEW.chapter_ordinal = OLD.chapter_ordinal
       AND NEW.segment_ordinal = OLD.segment_ordinal
       AND NEW.operation_key = OLD.operation_key
       AND NEW.spoken_text_ref = OLD.spoken_text_ref
       AND NEW.spoken_text_sha256 = OLD.spoken_text_sha256
       AND NEW.character_count = OLD.character_count
       AND NEW.boundary_kind = OLD.boundary_kind
       AND NEW.created_at = OLD.created_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Persisted speech manifest rows are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER segment_manifest_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON generation.segment_manifest
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER audiobook_chapter_plan_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON generation.audiobook_chapter_plan
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER speech_segment_update_guard
    BEFORE UPDATE ON generation.speech_segment
    FOR EACH ROW EXECUTE FUNCTION generation.reject_speech_segment_drift();
CREATE TRIGGER speech_segment_delete_guard
    BEFORE DELETE OR TRUNCATE ON generation.speech_segment
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER accepted_segment_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON generation.accepted_segment
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER packaged_audiobook_result_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON generation.packaged_audiobook_result
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER audiobook_asset_version_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON library.audiobook_asset_version
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER audiobook_chapter_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON library.audiobook_chapter
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();
CREATE TRIGGER final_asset_part_immutable
    BEFORE UPDATE OR DELETE OR TRUNCATE ON library.final_asset_part
    FOR EACH STATEMENT EXECUTE FUNCTION generation.reject_immutable_mutation();

ALTER TABLE generation.segment_manifest ENABLE ROW LEVEL SECURITY;
ALTER TABLE generation.audiobook_chapter_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE generation.speech_segment ENABLE ROW LEVEL SECURITY;
ALTER TABLE generation.speech_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE generation.accepted_segment ENABLE ROW LEVEL SECURITY;
ALTER TABLE generation.packaged_audiobook_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE generation.working_asset_erasure_obligation ENABLE ROW LEVEL SECURITY;
ALTER TABLE library.private_audiobook ENABLE ROW LEVEL SECURITY;
ALTER TABLE library.audiobook_asset_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE library.audiobook_chapter ENABLE ROW LEVEL SECURITY;
ALTER TABLE library.final_asset_part ENABLE ROW LEVEL SECURITY;

CREATE POLICY segment_manifest_listener_policy ON generation.segment_manifest
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY audiobook_chapter_plan_listener_policy ON generation.audiobook_chapter_plan
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY speech_segment_listener_policy ON generation.speech_segment
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY speech_attempt_listener_policy ON generation.speech_attempt
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY accepted_segment_listener_policy ON generation.accepted_segment
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY packaged_audiobook_result_listener_policy ON generation.packaged_audiobook_result
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY working_asset_erasure_obligation_listener_policy
    ON generation.working_asset_erasure_obligation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY private_audiobook_listener_policy ON library.private_audiobook
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY audiobook_asset_version_listener_policy ON library.audiobook_asset_version
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY audiobook_chapter_listener_policy ON library.audiobook_chapter
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY final_asset_part_listener_policy ON library.final_asset_part
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_speech_worker') THEN
        REVOKE cloudsqlsuperuser FROM folio_speech_worker;
        GRANT USAGE ON SCHEMA workflow, narration, generation TO folio_speech_worker;
        GRANT SELECT ON workflow.audiobook_conversion TO folio_speech_worker;
        GRANT SELECT ON narration.generation_recipe, narration.narration_plan,
            narration.narration_review_decision, narration.voice_mapping,
            narration.provider_capability_profile TO folio_speech_worker;
        GRANT SELECT, INSERT ON generation.segment_manifest,
            generation.audiobook_chapter_plan, generation.speech_segment,
            generation.speech_attempt, generation.accepted_segment TO folio_speech_worker;
        GRANT UPDATE (next_attempt_number) ON generation.speech_segment TO folio_speech_worker;
        GRANT UPDATE ON generation.speech_attempt TO folio_speech_worker;

        CREATE POLICY audiobook_conversion_speech_worker_policy
            ON workflow.audiobook_conversion TO folio_speech_worker USING (true);
        CREATE POLICY generation_recipe_speech_worker_policy
            ON narration.generation_recipe TO folio_speech_worker USING (true);
        CREATE POLICY narration_plan_speech_worker_policy
            ON narration.narration_plan TO folio_speech_worker USING (true);
        CREATE POLICY narration_review_speech_worker_policy
            ON narration.narration_review_decision TO folio_speech_worker USING (true);
        CREATE POLICY segment_manifest_speech_worker_policy
            ON generation.segment_manifest TO folio_speech_worker USING (true) WITH CHECK (true);
        CREATE POLICY chapter_plan_speech_worker_policy
            ON generation.audiobook_chapter_plan TO folio_speech_worker USING (true) WITH CHECK (true);
        CREATE POLICY speech_segment_speech_worker_policy
            ON generation.speech_segment TO folio_speech_worker USING (true) WITH CHECK (true);
        CREATE POLICY speech_attempt_speech_worker_policy
            ON generation.speech_attempt TO folio_speech_worker USING (true) WITH CHECK (true);
        CREATE POLICY accepted_segment_speech_worker_policy
            ON generation.accepted_segment TO folio_speech_worker USING (true) WITH CHECK (true);
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_packaging_worker') THEN
        REVOKE cloudsqlsuperuser FROM folio_packaging_worker;
        GRANT USAGE ON SCHEMA narration, generation TO folio_packaging_worker;
        GRANT SELECT ON narration.generation_recipe TO folio_packaging_worker;
        GRANT SELECT ON generation.segment_manifest,
            generation.audiobook_chapter_plan, generation.speech_segment,
            generation.accepted_segment, generation.packaged_audiobook_result
            TO folio_packaging_worker;
        GRANT INSERT ON generation.packaged_audiobook_result TO folio_packaging_worker;

        CREATE POLICY generation_recipe_packaging_worker_policy
            ON narration.generation_recipe TO folio_packaging_worker USING (true);
        CREATE POLICY segment_manifest_packaging_worker_policy
            ON generation.segment_manifest TO folio_packaging_worker USING (true);
        CREATE POLICY chapter_plan_packaging_worker_policy
            ON generation.audiobook_chapter_plan TO folio_packaging_worker USING (true);
        CREATE POLICY speech_segment_packaging_worker_policy
            ON generation.speech_segment TO folio_packaging_worker USING (true);
        CREATE POLICY accepted_segment_packaging_worker_policy
            ON generation.accepted_segment TO folio_packaging_worker USING (true);
        CREATE POLICY packaged_result_packaging_worker_policy
            ON generation.packaged_audiobook_result TO folio_packaging_worker
            USING (true) WITH CHECK (true);
    END IF;
END;
$$;
