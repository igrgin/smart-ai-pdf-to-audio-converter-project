ALTER TABLE narration.provider_capability_profile
    ADD COLUMN delivery_mode VARCHAR(24) NOT NULL DEFAULT 'SYNCHRONOUS',
    ADD COLUMN supported_inputs VARCHAR(32)[] NOT NULL DEFAULT ARRAY['CANONICAL_TEXT'],
    ADD COLUMN maximum_input_units BIGINT NOT NULL DEFAULT 4096 CHECK (maximum_input_units > 0),
    ADD COLUMN input_unit VARCHAR(32) NOT NULL DEFAULT 'UTF8_CHARACTER',
    ADD COLUMN quota_meter VARCHAR(64) NOT NULL DEFAULT 'REQUEST_PER_MINUTE',
    ADD COLUMN quota_limit BIGINT NOT NULL DEFAULT 500 CHECK (quota_limit > 0),
    ADD COLUMN quota_window_seconds INTEGER NOT NULL DEFAULT 60 CHECK (quota_window_seconds > 0),
    ADD COLUMN price_meter VARCHAR(64) NOT NULL DEFAULT 'INPUT_CHARACTER',
    ADD COLUMN request_format VARCHAR(80) NOT NULL DEFAULT 'application/json',
    ADD COLUMN response_format VARCHAR(80) NOT NULL DEFAULT 'application/json',
    ADD COLUMN native_controls JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN native_controls_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN privacy_state VARCHAR(24) NOT NULL DEFAULT 'QUALIFIED'
        CHECK (privacy_state IN ('QUALIFIED', 'STALE', 'BLOCKED')),
    ADD COLUMN region_state VARCHAR(24) NOT NULL DEFAULT 'QUALIFIED'
        CHECK (region_state IN ('QUALIFIED', 'STALE', 'BLOCKED')),
    ADD COLUMN access_state VARCHAR(24) NOT NULL DEFAULT 'QUALIFIED'
        CHECK (access_state IN ('QUALIFIED', 'STALE', 'BLOCKED')),
    ADD COLUMN quota_state VARCHAR(24) NOT NULL DEFAULT 'QUALIFIED'
        CHECK (quota_state IN ('QUALIFIED', 'STALE', 'BLOCKED')),
    ADD COLUMN evaluation_state VARCHAR(24) NOT NULL DEFAULT 'QUALIFIED'
        CHECK (evaluation_state IN ('QUALIFIED', 'STALE', 'BLOCKED'));

INSERT INTO narration.provider_capability_profile (
    profile_id, profile_version, provider, service, endpoint, model_snapshot, region,
    data_policy_version, supported_paces, profile_state, checked_at, expires_at,
    delivery_mode, supported_inputs, maximum_input_units, input_unit,
    quota_meter, quota_limit, quota_window_seconds, price_meter,
    request_format, response_format, native_controls, native_controls_schema,
    privacy_state, region_state, access_state, quota_state, evaluation_state
) VALUES (
    '20000000-0000-7000-8000-000000000002',
    'openai-speech-eu-v2',
    'openai',
    'speech',
    'https://eu.api.openai.com/v1/audio/speech',
    'gpt-4o-mini-tts-2025-12-15',
    'eu',
    'openai-eu-zdr-v1',
    ARRAY['MEASURED', 'NATURAL', 'BRISK'],
    'CURRENT',
    '2026-08-01T00:00:00Z',
    '2027-02-01T00:00:00Z',
    'SYNCHRONOUS',
    ARRAY['CANONICAL_TEXT'],
    4096,
    'UTF8_CHARACTER',
    'REQUEST_PER_MINUTE',
    500,
    60,
    'INPUT_CHARACTER',
    'application/json',
    'audio/wav',
    '{"response_format":"wav"}'::jsonb,
    '{"speed":{"minimum":0.25,"maximum":4.0},"instructions":{"type":"string"},"voice":{"type":"string"}}'::jsonb,
    'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED'
);

INSERT INTO narration.provider_capability_profile (
    profile_id, profile_version, provider, service, endpoint, model_snapshot, region,
    data_policy_version, supported_paces, profile_state, checked_at, expires_at,
    delivery_mode, supported_inputs, maximum_input_units, input_unit,
    quota_meter, quota_limit, quota_window_seconds, price_meter,
    request_format, response_format, native_controls, native_controls_schema,
    privacy_state, region_state, access_state, quota_state, evaluation_state
) VALUES
    (
        '20000000-0000-7000-8000-000000000011', 'openai-analysis-image-eu-v1',
        'openai', 'analysis', 'https://eu.api.openai.com/v1/responses',
        'gpt-5-mini-2025-08-07', 'eu', 'openai-eu-zdr-v1', ARRAY[]::VARCHAR(16)[],
        'CURRENT', '2026-08-01T00:00:00Z', '2027-02-01T00:00:00Z',
        'SYNCHRONOUS', ARRAY['CANONICAL_PAGE_IMAGE'], 8000000, 'IMAGE_BYTE',
        'REQUEST_PER_MINUTE', 500, 60, 'INPUT_OUTPUT_TOKEN',
        'application/json', 'application/json',
        '{"store":false,"structured_output":true}'::jsonb,
        '{"output_schema":{"type":"json_schema"}}'::jsonb,
        'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED'
    ),
    (
        '20000000-0000-7000-8000-000000000030', 'google-speech-eu-v1',
        'google', 'speech', 'https://eu-texttospeech.googleapis.com/v1/text:synthesize',
        'Neural2', 'eu', 'google-tts-eu-v1',
        ARRAY['MEASURED', 'NATURAL', 'BRISK'], 'CURRENT',
        '2026-08-01T00:00:00Z', '2027-02-01T00:00:00Z',
        'SYNCHRONOUS', ARRAY['CANONICAL_TEXT'], 5000, 'UTF8_BYTE',
        'REQUEST_PER_MINUTE', 1000, 60, 'INPUT_CHARACTER',
        'application/json', 'audio/wav', '{"audioEncoding":"LINEAR16"}'::jsonb,
        '{"speakingRate":{"minimum":0.25,"maximum":2.0},"voice":{"type":"string"}}'::jsonb,
        'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED'
    );

INSERT INTO narration.voice_mapping (
    mapping_id, narrator_voice_id, capability_profile_id, mapping_version, provider_voice,
    native_controls, required_region, required_data_policy_version,
    preview_version, evaluation_version, mapping_state
)
SELECT ids.mapping_id, old.narrator_voice_id,
       '20000000-0000-7000-8000-000000000002'::uuid,
       replace(old.mapping_version, '-v1', '-v2'), old.provider_voice, old.native_controls,
       'eu', 'openai-eu-zdr-v1', old.preview_version, old.evaluation_version, 'CURRENT'
FROM narration.voice_mapping old
JOIN (VALUES
    ('10000000-0000-7000-8000-000000000001'::uuid, '30000000-0000-7000-8000-000000000101'::uuid),
    ('10000000-0000-7000-8000-000000000002'::uuid, '30000000-0000-7000-8000-000000000102'::uuid),
    ('10000000-0000-7000-8000-000000000003'::uuid, '30000000-0000-7000-8000-000000000103'::uuid),
    ('10000000-0000-7000-8000-000000000004'::uuid, '30000000-0000-7000-8000-000000000104'::uuid),
    ('10000000-0000-7000-8000-000000000005'::uuid, '30000000-0000-7000-8000-000000000105'::uuid),
    ('10000000-0000-7000-8000-000000000006'::uuid, '30000000-0000-7000-8000-000000000106'::uuid)
) ids(voice_id, mapping_id) ON ids.voice_id = old.narrator_voice_id
WHERE old.capability_profile_id = '20000000-0000-7000-8000-000000000001';

UPDATE narration.voice_mapping
SET mapping_state = 'RETIRED'
WHERE capability_profile_id = '20000000-0000-7000-8000-000000000001';

UPDATE narration.provider_capability_profile
SET profile_state = 'RETIRED'
WHERE profile_id = '20000000-0000-7000-8000-000000000001';

INSERT INTO narration.voice_mapping (
    mapping_id, narrator_voice_id, capability_profile_id, mapping_version, provider_voice,
    native_controls, required_region, required_data_policy_version,
    preview_version, evaluation_version, mapping_state
)
SELECT ids.mapping_id, ids.voice_id, '20000000-0000-7000-8000-000000000030'::uuid,
       ids.mapping_version, ids.provider_voice,
       '{"MEASURED":{"speakingRate":0.88},"NATURAL":{"speakingRate":1.0},"BRISK":{"speakingRate":1.12}}'::jsonb,
       'eu', 'google-tts-eu-v1', 'folio-preview-v1', 'cross-provider-speech-eval-2026-08', 'CURRENT'
FROM (VALUES
    ('10000000-0000-7000-8000-000000000001'::uuid, '30000000-0000-7000-8000-000000000201'::uuid, 'rowan-google-v1', 'en-GB-Neural2-F'),
    ('10000000-0000-7000-8000-000000000002'::uuid, '30000000-0000-7000-8000-000000000202'::uuid, 'marlowe-google-v1', 'en-US-Neural2-J'),
    ('10000000-0000-7000-8000-000000000003'::uuid, '30000000-0000-7000-8000-000000000203'::uuid, 'ellis-google-v1', 'en-GB-Neural2-F'),
    ('10000000-0000-7000-8000-000000000004'::uuid, '30000000-0000-7000-8000-000000000204'::uuid, 'callum-google-v1', 'en-GB-Neural2-D'),
    ('10000000-0000-7000-8000-000000000005'::uuid, '30000000-0000-7000-8000-000000000205'::uuid, 'ansel-google-v1', 'en-AU-Neural2-B'),
    ('10000000-0000-7000-8000-000000000006'::uuid, '30000000-0000-7000-8000-000000000206'::uuid, 'sloane-google-v1', 'en-US-Neural2-F')
) ids(voice_id, mapping_id, mapping_version, provider_voice);

CREATE TABLE narration.qualified_voice_equivalence (
    equivalence_id UUID PRIMARY KEY,
    primary_mapping_id UUID NOT NULL REFERENCES narration.voice_mapping(mapping_id),
    failover_mapping_id UUID NOT NULL REFERENCES narration.voice_mapping(mapping_id),
    pace VARCHAR(16) NOT NULL CHECK (pace IN ('MEASURED', 'NATURAL', 'BRISK')),
    voice_equivalence_version VARCHAR(100) NOT NULL,
    pace_equivalence_version VARCHAR(100) NOT NULL,
    evaluation_state VARCHAR(24) NOT NULL CHECK (evaluation_state IN ('QUALIFIED', 'STALE', 'BLOCKED')),
    checked_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > checked_at),
    UNIQUE (primary_mapping_id, failover_mapping_id, pace)
);

INSERT INTO narration.qualified_voice_equivalence (
    equivalence_id, primary_mapping_id, failover_mapping_id, pace,
    voice_equivalence_version, pace_equivalence_version,
    evaluation_state, checked_at, expires_at
)
SELECT md5(ids.primary_mapping_id::text || ids.failover_mapping_id::text || paces.pace)::uuid,
       ids.primary_mapping_id, ids.failover_mapping_id, paces.pace,
       'voice-equivalence-2026-08', 'pace-' || lower(paces.pace) || '-equivalence-2026-08',
       'QUALIFIED', '2026-08-01T00:00:00Z', '2027-02-01T00:00:00Z'
FROM (VALUES
    ('30000000-0000-7000-8000-000000000001'::uuid, '30000000-0000-7000-8000-000000000201'::uuid),
    ('30000000-0000-7000-8000-000000000002'::uuid, '30000000-0000-7000-8000-000000000202'::uuid),
    ('30000000-0000-7000-8000-000000000003'::uuid, '30000000-0000-7000-8000-000000000203'::uuid),
    ('30000000-0000-7000-8000-000000000004'::uuid, '30000000-0000-7000-8000-000000000204'::uuid),
    ('30000000-0000-7000-8000-000000000005'::uuid, '30000000-0000-7000-8000-000000000205'::uuid),
    ('30000000-0000-7000-8000-000000000006'::uuid, '30000000-0000-7000-8000-000000000206'::uuid),
    ('30000000-0000-7000-8000-000000000101'::uuid, '30000000-0000-7000-8000-000000000201'::uuid),
    ('30000000-0000-7000-8000-000000000102'::uuid, '30000000-0000-7000-8000-000000000202'::uuid),
    ('30000000-0000-7000-8000-000000000103'::uuid, '30000000-0000-7000-8000-000000000203'::uuid),
    ('30000000-0000-7000-8000-000000000104'::uuid, '30000000-0000-7000-8000-000000000204'::uuid),
    ('30000000-0000-7000-8000-000000000105'::uuid, '30000000-0000-7000-8000-000000000205'::uuid),
    ('30000000-0000-7000-8000-000000000106'::uuid, '30000000-0000-7000-8000-000000000206'::uuid)
) ids(primary_mapping_id, failover_mapping_id)
CROSS JOIN unnest(ARRAY['MEASURED', 'NATURAL', 'BRISK']) AS paces(pace);

INSERT INTO narration.provider_capability_profile (
    profile_id, profile_version, provider, service, endpoint, model_snapshot, region,
    data_policy_version, supported_paces, profile_state, checked_at, expires_at,
    delivery_mode, supported_inputs, maximum_input_units, input_unit,
    quota_meter, quota_limit, quota_window_seconds, price_meter,
    request_format, response_format, native_controls, native_controls_schema,
    privacy_state, region_state, access_state, quota_state, evaluation_state
) VALUES
    (
        '20000000-0000-7000-8000-000000000020',
        'google-analysis-text-eu-v1',
        'google', 'analysis',
        'https://europe-west1-aiplatform.googleapis.com/v1/projects/{project}/locations/europe-west1/publishers/google/models/{model}:generateContent',
        'gemini-2.5-flash-001', 'europe-west1', 'google-vertex-zdr-v1',
        ARRAY[]::VARCHAR(16)[], 'CURRENT',
        '2026-08-01T00:00:00Z', '2027-02-01T00:00:00Z',
        'SYNCHRONOUS', ARRAY['CANONICAL_TEXT'], 250000, 'UTF8_CHARACTER',
        'TOKEN_PER_MINUTE', 1000000, 60, 'INPUT_OUTPUT_TOKEN',
        'application/json', 'application/json',
        '{"responseMimeType":"application/json"}'::jsonb,
        '{"responseSchema":{"type":"OBJECT"}}'::jsonb,
        'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED'
    ),
    (
        '20000000-0000-7000-8000-000000000021',
        'google-analysis-image-eu-v1',
        'google', 'analysis',
        'https://europe-west1-aiplatform.googleapis.com/v1/projects/{project}/locations/europe-west1/publishers/google/models/{model}:generateContent',
        'gemini-2.5-flash-001', 'europe-west1', 'google-vertex-zdr-v1',
        ARRAY[]::VARCHAR(16)[], 'CURRENT',
        '2026-08-01T00:00:00Z', '2027-02-01T00:00:00Z',
        'SYNCHRONOUS', ARRAY['CANONICAL_PAGE_IMAGE'], 10000000, 'IMAGE_BYTE',
        'TOKEN_PER_MINUTE', 1000000, 60, 'INPUT_OUTPUT_TOKEN',
        'application/json', 'application/json',
        '{"responseMimeType":"application/json"}'::jsonb,
        '{"responseSchema":{"type":"OBJECT"}}'::jsonb,
        'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED'
    );

INSERT INTO narration.provider_capability_profile (
    profile_id, profile_version, provider, service, endpoint, model_snapshot, region,
    data_policy_version, supported_paces, profile_state, checked_at, expires_at,
    delivery_mode, supported_inputs, maximum_input_units, input_unit,
    quota_meter, quota_limit, quota_window_seconds, price_meter,
    request_format, response_format, native_controls, native_controls_schema,
    privacy_state, region_state, access_state, quota_state, evaluation_state
) VALUES (
    '20000000-0000-7000-8000-000000000010',
    'openai-analysis-eu-v1',
    'openai',
    'analysis',
    'https://eu.api.openai.com/v1/responses',
    'gpt-5-mini-2025-08-07',
    'eu',
    'openai-eu-zdr-v1',
    ARRAY[]::VARCHAR(16)[],
    'CURRENT',
    '2026-08-01T00:00:00Z',
    '2027-02-01T00:00:00Z',
    'SYNCHRONOUS',
    ARRAY['CANONICAL_TEXT'],
    250000,
    'UTF8_CHARACTER',
    'REQUEST_PER_MINUTE',
    500,
    60,
    'INPUT_OUTPUT_TOKEN',
    'application/json',
    'application/json',
    '{"store":false,"structured_output":true}'::jsonb,
    '{"output_schema":{"type":"json_schema"}}'::jsonb,
    'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED', 'QUALIFIED'
);

CREATE SCHEMA IF NOT EXISTS provider;

CREATE TABLE provider.operation_evidence (
    operation_id VARCHAR(100) PRIMARY KEY,
    service VARCHAR(24) NOT NULL CHECK (service IN ('ANALYSIS', 'SPEECH')),
    capability_profile_id UUID NOT NULL REFERENCES narration.provider_capability_profile(profile_id),
    capability_profile_version VARCHAR(100) NOT NULL,
    generation_recipe_id UUID NOT NULL REFERENCES narration.generation_recipe(recipe_id),
    provider_request_id VARCHAR(200),
    actual_model VARCHAR(160) NOT NULL,
    model_evidence_source VARCHAR(32) NOT NULL CHECK (
        model_evidence_source IN ('PROVIDER_RESPONSE', 'REQUESTED_MODEL', 'QUALIFIED_VOICE_TIER')
    ),
    actual_region VARCHAR(80) NOT NULL,
    input_meter VARCHAR(64) NOT NULL,
    input_units BIGINT NOT NULL CHECK (input_units >= 0),
    output_meter VARCHAR(64) NOT NULL,
    output_units BIGINT NOT NULL CHECK (output_units >= 0),
    price_meter VARCHAR(64) NOT NULL,
    outcome_sha256 CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE generation.speech_attempt
    ADD COLUMN capability_profile_version VARCHAR(100),
    ADD COLUMN input_meter VARCHAR(64),
    ADD COLUMN input_units BIGINT CHECK (input_units >= 0),
    ADD COLUMN output_meter VARCHAR(64),
    ADD COLUMN output_units BIGINT CHECK (output_units >= 0);

ALTER TABLE generation.segment_manifest DROP CONSTRAINT segment_manifest_conversion_id_key;

CREATE TABLE generation.active_segment_manifest (
    conversion_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    manifest_id UUID NOT NULL UNIQUE REFERENCES generation.segment_manifest(manifest_id),
    activated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (manifest_id, listener_id, conversion_id)
        REFERENCES generation.segment_manifest(manifest_id, listener_id, conversion_id)
);

INSERT INTO generation.active_segment_manifest (conversion_id, listener_id, manifest_id, activated_at)
SELECT conversion_id, listener_id, manifest_id, created_at FROM generation.segment_manifest;

ALTER TABLE generation.active_segment_manifest ENABLE ROW LEVEL SECURITY;
CREATE POLICY active_segment_manifest_listener_policy ON generation.active_segment_manifest
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_speech_worker') THEN
        GRANT SELECT, INSERT, UPDATE ON generation.active_segment_manifest TO folio_speech_worker;
        GRANT USAGE ON SCHEMA provider TO folio_speech_worker;
        GRANT SELECT, INSERT ON provider.operation_evidence TO folio_speech_worker;
        CREATE POLICY active_segment_manifest_speech_worker_policy
            ON generation.active_segment_manifest TO folio_speech_worker USING (true) WITH CHECK (true);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_packaging_worker') THEN
        GRANT SELECT ON generation.active_segment_manifest TO folio_packaging_worker;
        CREATE POLICY active_segment_manifest_packaging_worker_policy
            ON generation.active_segment_manifest TO folio_packaging_worker USING (true);
    END IF;
END;
$$;

CREATE FUNCTION provider.reject_operation_evidence_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Provider operation evidence is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER provider_operation_evidence_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON provider.operation_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION provider.reject_operation_evidence_mutation();
