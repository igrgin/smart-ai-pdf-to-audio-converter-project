CREATE SCHEMA IF NOT EXISTS narration;

CREATE TABLE narration.narrator_voice (
    voice_id UUID PRIMARY KEY,
    catalog_ordinal INTEGER NOT NULL UNIQUE CHECK (catalog_ordinal > 0),
    display_name VARCHAR(80) NOT NULL UNIQUE,
    english_variety VARCHAR(80) NOT NULL,
    descriptor_primary VARCHAR(80) NOT NULL,
    descriptor_secondary VARCHAR(80) NOT NULL,
    descriptor_review_version VARCHAR(80) NOT NULL,
    availability VARCHAR(32) NOT NULL CHECK (availability IN (
        'AVAILABLE', 'TEMPORARILY_UNAVAILABLE', 'RETIRED'
    )),
    preview_uri VARCHAR(300) NOT NULL,
    preview_passage_version VARCHAR(80) NOT NULL,
    preview_duration_seconds INTEGER NOT NULL CHECK (preview_duration_seconds BETWEEN 25 AND 35),
    preview_ai_generated BOOLEAN NOT NULL
);

CREATE TABLE narration.provider_capability_profile (
    profile_id UUID PRIMARY KEY,
    profile_version VARCHAR(100) NOT NULL UNIQUE,
    provider VARCHAR(80) NOT NULL,
    service VARCHAR(80) NOT NULL,
    endpoint VARCHAR(300) NOT NULL,
    model_snapshot VARCHAR(160) NOT NULL,
    region VARCHAR(80) NOT NULL,
    data_policy_version VARCHAR(100) NOT NULL,
    supported_paces VARCHAR(16)[] NOT NULL,
    profile_state VARCHAR(24) NOT NULL CHECK (profile_state IN ('CURRENT', 'STALE', 'RETIRED')),
    checked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > checked_at)
);

CREATE TABLE narration.voice_mapping (
    mapping_id UUID PRIMARY KEY,
    narrator_voice_id UUID NOT NULL REFERENCES narration.narrator_voice(voice_id),
    capability_profile_id UUID NOT NULL REFERENCES narration.provider_capability_profile(profile_id),
    mapping_version VARCHAR(100) NOT NULL UNIQUE,
    provider_voice VARCHAR(100) NOT NULL,
    native_controls JSONB NOT NULL,
    required_region VARCHAR(80) NOT NULL,
    required_data_policy_version VARCHAR(100) NOT NULL,
    preview_version VARCHAR(80) NOT NULL,
    evaluation_version VARCHAR(80) NOT NULL,
    mapping_state VARCHAR(24) NOT NULL CHECK (mapping_state IN ('CURRENT', 'STALE', 'RETIRED')),
    UNIQUE (narrator_voice_id, capability_profile_id, mapping_version)
);

CREATE TABLE narration.generation_recipe (
    recipe_id UUID PRIMARY KEY,
    conversion_id UUID NOT NULL REFERENCES workflow.audiobook_conversion(conversion_id),
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    supersedes_recipe_id UUID REFERENCES narration.generation_recipe(recipe_id),
    narrator_voice_id UUID NOT NULL REFERENCES narration.narrator_voice(voice_id),
    voice_display_name VARCHAR(80) NOT NULL,
    pace VARCHAR(16) NOT NULL CHECK (pace IN ('MEASURED', 'NATURAL', 'BRISK')),
    capability_profile_id UUID NOT NULL REFERENCES narration.provider_capability_profile(profile_id),
    capability_profile_version VARCHAR(100) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    service VARCHAR(80) NOT NULL,
    endpoint VARCHAR(300) NOT NULL,
    model_snapshot VARCHAR(160) NOT NULL,
    region VARCHAR(80) NOT NULL,
    data_policy_version VARCHAR(100) NOT NULL,
    voice_mapping_id UUID NOT NULL REFERENCES narration.voice_mapping(mapping_id),
    mapping_version VARCHAR(100) NOT NULL,
    provider_voice VARCHAR(100) NOT NULL,
    native_controls JSONB NOT NULL,
    preview_version VARCHAR(80) NOT NULL,
    evaluation_version VARCHAR(80) NOT NULL,
    segmentation_policy_version VARCHAR(100) NOT NULL,
    audio_policy_version VARCHAR(100) NOT NULL,
    toolchain_version VARCHAR(100) NOT NULL,
    recipe_digest CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (recipe_id, conversion_id, listener_id)
);

ALTER TABLE workflow.audiobook_conversion
    ADD COLUMN current_generation_recipe_id UUID;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_current_recipe_fk
    FOREIGN KEY (current_generation_recipe_id, conversion_id, listener_id)
    REFERENCES narration.generation_recipe(recipe_id, conversion_id, listener_id);
ALTER TABLE workflow.audiobook_conversion
    DROP CONSTRAINT audiobook_conversion_state_check;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_state_check
    CHECK (state IN ('PREPARING', 'GENERATING'));

CREATE INDEX generation_recipe_conversion_idx
    ON narration.generation_recipe(conversion_id, created_at);

CREATE TABLE narration.recipe_confirmation_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    conversion_id UUID NOT NULL REFERENCES workflow.audiobook_conversion(conversion_id),
    request_fingerprint CHAR(64) NOT NULL,
    recipe_id UUID NOT NULL REFERENCES narration.generation_recipe(recipe_id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION narration.reject_generation_recipe_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Generation Recipes are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER generation_recipe_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON narration.generation_recipe
    FOR EACH STATEMENT EXECUTE FUNCTION narration.reject_generation_recipe_mutation();

CREATE TRIGGER recipe_confirmation_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON narration.recipe_confirmation_operation
    FOR EACH STATEMENT EXECUTE FUNCTION narration.reject_generation_recipe_mutation();

ALTER TABLE narration.generation_recipe ENABLE ROW LEVEL SECURITY;
ALTER TABLE narration.recipe_confirmation_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY generation_recipe_listener_policy ON narration.generation_recipe
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY recipe_confirmation_operation_listener_policy ON narration.recipe_confirmation_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

INSERT INTO narration.narrator_voice (
    voice_id, catalog_ordinal, display_name, english_variety,
    descriptor_primary, descriptor_secondary, descriptor_review_version, availability,
    preview_uri, preview_passage_version, preview_duration_seconds, preview_ai_generated
) VALUES
    ('10000000-0000-7000-8000-000000000001', 1, 'Rowan', 'British English',
     'Warm', 'Grounded', 'voice-review-2026-07', 'AVAILABLE',
     '/samples/narrator-voices/rowan-folio-preview-v1.mp3', 'folio-preview-v1', 29, TRUE),
    ('10000000-0000-7000-8000-000000000002', 2, 'Marlowe', 'American English',
     'Clear', 'Assured', 'voice-review-2026-07', 'AVAILABLE',
     '/samples/narrator-voices/marlowe-folio-preview-v1.mp3', 'folio-preview-v1', 29, TRUE),
    ('10000000-0000-7000-8000-000000000003', 3, 'Ellis', 'Irish English',
     'Bright', 'Expressive', 'voice-review-2026-07', 'AVAILABLE',
     '/samples/narrator-voices/ellis-folio-preview-v1.mp3', 'folio-preview-v1', 29, TRUE),
    ('10000000-0000-7000-8000-000000000004', 4, 'Clara', 'British English',
     'Calm', 'Intimate', 'voice-review-2026-07', 'AVAILABLE',
     '/samples/narrator-voices/clara-folio-preview-v1.mp3', 'folio-preview-v1', 29, TRUE),
    ('10000000-0000-7000-8000-000000000005', 5, 'Ansel', 'Australian English',
     'Open', 'Conversational', 'voice-review-2026-07', 'AVAILABLE',
     '/samples/narrator-voices/ansel-folio-preview-v1.mp3', 'folio-preview-v1', 29, TRUE),
    ('10000000-0000-7000-8000-000000000006', 6, 'Sloane', 'American English',
     'Poised', 'Reflective', 'voice-review-2026-07', 'AVAILABLE',
     '/samples/narrator-voices/sloane-folio-preview-v1.mp3', 'folio-preview-v1', 29, TRUE);

INSERT INTO narration.provider_capability_profile (
    profile_id, profile_version, provider, service, endpoint, model_snapshot, region,
    data_policy_version, supported_paces, profile_state, checked_at, expires_at
) VALUES (
    '20000000-0000-7000-8000-000000000001', 'openai-speech-eu-v1', 'openai', 'speech',
    'https://api.openai.com/v1/audio/speech', 'gpt-4o-mini-tts-2025-12-15', 'eu',
    'eu-private-v1', ARRAY['MEASURED', 'NATURAL', 'BRISK'], 'CURRENT',
    '2026-07-15T00:00:00Z', '2030-01-01T00:00:00Z'
);

INSERT INTO narration.voice_mapping (
    mapping_id, narrator_voice_id, capability_profile_id, mapping_version, provider_voice,
    native_controls, required_region, required_data_policy_version,
    preview_version, evaluation_version, mapping_state
) VALUES
    ('30000000-0000-7000-8000-000000000001', '10000000-0000-7000-8000-000000000001', '20000000-0000-7000-8000-000000000001', 'rowan-openai-v1', 'cedar',
     '{"MEASURED":{"speed":0.88,"instructions":"Measured, warm audiobook narration."},"NATURAL":{"speed":1.0,"instructions":"Natural, warm audiobook narration."},"BRISK":{"speed":1.12,"instructions":"Brisk, warm audiobook narration."}}', 'eu', 'eu-private-v1', 'folio-preview-v1', 'speech-eval-2026-07', 'CURRENT'),
    ('30000000-0000-7000-8000-000000000002', '10000000-0000-7000-8000-000000000002', '20000000-0000-7000-8000-000000000001', 'marlowe-openai-v1', 'marin',
     '{"MEASURED":{"speed":0.88,"instructions":"Measured, clear audiobook narration."},"NATURAL":{"speed":1.0,"instructions":"Natural, clear audiobook narration."},"BRISK":{"speed":1.12,"instructions":"Brisk, clear audiobook narration."}}', 'eu', 'eu-private-v1', 'folio-preview-v1', 'speech-eval-2026-07', 'CURRENT'),
    ('30000000-0000-7000-8000-000000000003', '10000000-0000-7000-8000-000000000003', '20000000-0000-7000-8000-000000000001', 'ellis-openai-v1', 'coral',
     '{"MEASURED":{"speed":0.88,"instructions":"Measured, bright audiobook narration."},"NATURAL":{"speed":1.0,"instructions":"Natural, bright audiobook narration."},"BRISK":{"speed":1.12,"instructions":"Brisk, bright audiobook narration."}}', 'eu', 'eu-private-v1', 'folio-preview-v1', 'speech-eval-2026-07', 'CURRENT'),
    ('30000000-0000-7000-8000-000000000004', '10000000-0000-7000-8000-000000000004', '20000000-0000-7000-8000-000000000001', 'clara-openai-v1', 'shimmer',
     '{"MEASURED":{"speed":0.88,"instructions":"Measured, calm audiobook narration."},"NATURAL":{"speed":1.0,"instructions":"Natural, calm audiobook narration."},"BRISK":{"speed":1.12,"instructions":"Brisk, calm audiobook narration."}}', 'eu', 'eu-private-v1', 'folio-preview-v1', 'speech-eval-2026-07', 'CURRENT'),
    ('30000000-0000-7000-8000-000000000005', '10000000-0000-7000-8000-000000000005', '20000000-0000-7000-8000-000000000001', 'ansel-openai-v1', 'ash',
     '{"MEASURED":{"speed":0.88,"instructions":"Measured, conversational audiobook narration."},"NATURAL":{"speed":1.0,"instructions":"Natural, conversational audiobook narration."},"BRISK":{"speed":1.12,"instructions":"Brisk, conversational audiobook narration."}}', 'eu', 'eu-private-v1', 'folio-preview-v1', 'speech-eval-2026-07', 'CURRENT'),
    ('30000000-0000-7000-8000-000000000006', '10000000-0000-7000-8000-000000000006', '20000000-0000-7000-8000-000000000001', 'sloane-openai-v1', 'sage',
     '{"MEASURED":{"speed":0.88,"instructions":"Measured, reflective audiobook narration."},"NATURAL":{"speed":1.0,"instructions":"Natural, reflective audiobook narration."},"BRISK":{"speed":1.12,"instructions":"Brisk, reflective audiobook narration."}}', 'eu', 'eu-private-v1', 'folio-preview-v1', 'speech-eval-2026-07', 'CURRENT');
