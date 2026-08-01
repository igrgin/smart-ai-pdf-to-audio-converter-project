ALTER TABLE workflow.audiobook_conversion
    ADD COLUMN pause_responsible_party VARCHAR(24),
    ADD COLUMN safe_resume_stage VARCHAR(32),
    ADD COLUMN pause_deadline TIMESTAMPTZ;

ALTER TABLE workflow.narration_plan_outbox
    ADD COLUMN expected_conversion_version BIGINT;
UPDATE workflow.narration_plan_outbox outbox
SET expected_conversion_version = conversion.version
FROM workflow.narration_plan_work work
JOIN workflow.audiobook_conversion conversion
  ON conversion.conversion_id = work.conversion_id
WHERE outbox.work_id = work.work_id;
ALTER TABLE workflow.narration_plan_outbox
    ALTER COLUMN expected_conversion_version SET NOT NULL;

UPDATE workflow.audiobook_conversion
SET pause_responsible_party = 'LISTENER', safe_resume_stage = 'NARRATION_ANALYSIS'
WHERE state = 'PAUSED';

ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_pause_context_check CHECK (
        (state = 'PAUSED') = (pause_responsible_party IS NOT NULL AND safe_resume_stage IS NOT NULL)
    ),
    ADD CONSTRAINT audiobook_conversion_pause_responsible_party_check CHECK (
        pause_responsible_party IS NULL OR pause_responsible_party IN ('LISTENER', 'PLATFORM', 'PROVIDER', 'OPERATOR')
    );

ALTER TABLE workflow.audiobook_conversion
    DROP CONSTRAINT audiobook_conversion_state_check;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_state_check CHECK (state IN (
        'PREPARING', 'AWAITING_REVIEW', 'GENERATING', 'FINALIZING',
        'FINALIZED', 'PAUSED', 'FAILED', 'CANCELLED'
    ));

CREATE TABLE workflow.conversion_stage_run (
    stage_run_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    stage VARCHAR(32) NOT NULL CHECK (stage IN (
        'INSPECTION', 'EXTRACTION', 'NARRATION_ANALYSIS', 'SPEECH',
        'ASSEMBLY', 'PACKAGING', 'FINALIZATION'
    )),
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'READY', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'PAUSED', 'CANCELLED'
    )),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    maximum_attempts INTEGER NOT NULL CHECK (maximum_attempts BETWEEN 1 AND 20),
    lease_owner VARCHAR(160),
    lease_message_id UUID,
    lease_expires_at TIMESTAMPTZ,
    checkpoint_reference VARCHAR(300),
    checkpoint_sha256 CHAR(64),
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id),
    UNIQUE (conversion_id, stage),
    CHECK ((checkpoint_reference IS NULL) = (checkpoint_sha256 IS NULL)),
    CHECK (state = 'CLAIMED' OR (lease_owner IS NULL AND lease_message_id IS NULL AND lease_expires_at IS NULL))
);

INSERT INTO workflow.conversion_stage_run (
    stage_run_id, listener_id, conversion_id, stage, state,
    attempt_count, maximum_attempts, failure_code, created_at, updated_at
)
SELECT gen_random_uuid(), conversion.listener_id, conversion.conversion_id,
       CASE
           WHEN conversion.state = 'GENERATING' THEN 'SPEECH'
           WHEN conversion.state = 'FINALIZING' THEN 'FINALIZATION'
           WHEN conversion.reason_code = 'EXTRACTION_PENDING' THEN 'EXTRACTION'
           ELSE 'NARRATION_ANALYSIS'
       END,
       CASE WHEN conversion.state = 'PAUSED' THEN 'PAUSED' ELSE 'READY' END,
       COALESCE(work.attempt_count, 0), 4,
       CASE WHEN conversion.state = 'PAUSED' THEN conversion.reason_code ELSE NULL END,
       conversion.created_at, CURRENT_TIMESTAMP
FROM workflow.audiobook_conversion conversion
LEFT JOIN workflow.narration_plan_work work ON work.conversion_id = conversion.conversion_id
WHERE conversion.state IN ('PREPARING', 'GENERATING', 'FINALIZING', 'PAUSED')
ON CONFLICT (conversion_id, stage) DO NOTHING;

CREATE TABLE workflow.conversion_message_inbox (
    message_id UUID PRIMARY KEY,
    conversion_id UUID NOT NULL,
    stage_run_id UUID REFERENCES workflow.conversion_stage_run(stage_run_id),
    stage VARCHAR(32) NOT NULL,
    schema_version INTEGER NOT NULL,
    expected_conversion_version BIGINT NOT NULL,
    disposition VARCHAR(24) NOT NULL CHECK (disposition IN (
        'CLAIMED', 'DUPLICATE', 'STALE', 'REJECTED', 'LATE', 'DEAD_LETTERED'
    )),
    reason_code VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE workflow.conversion_accepted_result (
    accepted_result_id UUID PRIMARY KEY,
    stage_run_id UUID NOT NULL REFERENCES workflow.conversion_stage_run(stage_run_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    stage VARCHAR(32) NOT NULL,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    result_reference VARCHAR(300) NOT NULL UNIQUE,
    result_sha256 CHAR(64) NOT NULL,
    provider_work BOOLEAN NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id),
    UNIQUE (accepted_result_id, listener_id, conversion_id)
);

CREATE TABLE workflow.conversion_repair_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    stage VARCHAR(32) NOT NULL,
    expected_conversion_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.conversion_pause_event (
    pause_event_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    responsible_party VARCHAR(24) NOT NULL,
    safe_resume_stage VARCHAR(32) NOT NULL,
    deadline TIMESTAMPTZ,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.conversion_resume_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    expected_conversion_version BIGINT NOT NULL,
    safe_resume_stage VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.conversion_cancellation_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    expected_conversion_version BIGINT NOT NULL,
    incurred_provider_cost_micros BIGINT NOT NULL CHECK (incurred_provider_cost_micros >= 0),
    request_reason VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.conversion_terminal_failure_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    expected_conversion_version BIGINT NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    reusable_characters BIGINT NOT NULL CHECK (reusable_characters >= 0),
    incurred_provider_cost_micros BIGINT NOT NULL CHECK (incurred_provider_cost_micros >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.conversion_cleanup_obligation (
    obligation_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'CLEANING', 'COMPLETED')),
    reason_code VARCHAR(64) NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.conversion_late_result (
    late_result_id UUID PRIMARY KEY,
    message_id UUID NOT NULL,
    conversion_id UUID NOT NULL,
    stage VARCHAR(32) NOT NULL,
    operation_key VARCHAR(200) NOT NULL,
    result_reference VARCHAR(300) NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    provider_work BOOLEAN NOT NULL,
    terminal_state VARCHAR(24) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    UNIQUE (message_id, operation_key)
);

CREATE TABLE workflow.conversion_provider_cost_entry (
    entry_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    incurred_provider_cost_micros BIGINT NOT NULL CHECK (incurred_provider_cost_micros > 0),
    evidence_reference VARCHAR(300) NOT NULL UNIQUE,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE INDEX conversion_stage_claim_idx
    ON workflow.conversion_stage_run(state, lease_expires_at, updated_at);
CREATE INDEX conversion_message_conversion_idx
    ON workflow.conversion_message_inbox(conversion_id, received_at);

CREATE FUNCTION workflow.reject_workflow_evidence_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Conversion workflow evidence is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER conversion_message_inbox_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_message_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_accepted_result_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_accepted_result
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_repair_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_repair_operation
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_pause_event_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_pause_event
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_resume_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_resume_operation
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_cancellation_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_cancellation_operation
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_terminal_failure_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_terminal_failure_operation
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_late_result_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_late_result
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();
CREATE TRIGGER conversion_provider_cost_entry_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.conversion_provider_cost_entry
    FOR EACH STATEMENT EXECUTE FUNCTION workflow.reject_workflow_evidence_mutation();

ALTER TABLE workflow.conversion_stage_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_accepted_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_repair_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_pause_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_resume_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_cancellation_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_terminal_failure_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_cleanup_obligation ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.conversion_provider_cost_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY conversion_stage_run_listener_policy ON workflow.conversion_stage_run
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_accepted_result_listener_policy ON workflow.conversion_accepted_result
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_repair_operation_listener_policy ON workflow.conversion_repair_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_pause_event_listener_policy ON workflow.conversion_pause_event
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_resume_operation_listener_policy ON workflow.conversion_resume_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_cancellation_operation_listener_policy ON workflow.conversion_cancellation_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_terminal_failure_operation_listener_policy
    ON workflow.conversion_terminal_failure_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_cleanup_obligation_listener_policy ON workflow.conversion_cleanup_obligation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY conversion_provider_cost_entry_listener_policy ON workflow.conversion_provider_cost_entry
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_narration_worker') THEN
        GRANT SELECT, INSERT, UPDATE ON workflow.conversion_stage_run TO folio_narration_worker;
        GRANT SELECT, INSERT ON workflow.conversion_message_inbox,
            workflow.conversion_accepted_result, workflow.conversion_pause_event,
            workflow.conversion_late_result TO folio_narration_worker;
        GRANT UPDATE ON workflow.audiobook_conversion TO folio_narration_worker;
        CREATE POLICY conversion_stage_run_narration_worker_policy
            ON workflow.conversion_stage_run TO folio_narration_worker USING (true) WITH CHECK (true);
        CREATE POLICY conversion_accepted_result_narration_worker_policy
            ON workflow.conversion_accepted_result TO folio_narration_worker USING (true) WITH CHECK (true);
        CREATE POLICY conversion_pause_event_narration_worker_policy
            ON workflow.conversion_pause_event TO folio_narration_worker USING (true) WITH CHECK (true);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_speech_worker') THEN
        GRANT SELECT, INSERT, UPDATE ON workflow.conversion_stage_run TO folio_speech_worker;
        GRANT SELECT, INSERT ON workflow.conversion_message_inbox,
            workflow.conversion_accepted_result, workflow.conversion_late_result,
            workflow.conversion_provider_cost_entry TO folio_speech_worker;
        CREATE POLICY conversion_stage_run_speech_worker_policy
            ON workflow.conversion_stage_run TO folio_speech_worker USING (true) WITH CHECK (true);
        CREATE POLICY conversion_accepted_result_speech_worker_policy
            ON workflow.conversion_accepted_result TO folio_speech_worker USING (true) WITH CHECK (true);
        CREATE POLICY conversion_provider_cost_speech_worker_policy
            ON workflow.conversion_provider_cost_entry TO folio_speech_worker USING (true) WITH CHECK (true);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_packaging_worker') THEN
        GRANT USAGE ON SCHEMA workflow TO folio_packaging_worker;
        GRANT SELECT ON workflow.audiobook_conversion TO folio_packaging_worker;
        GRANT SELECT, INSERT, UPDATE ON workflow.conversion_stage_run TO folio_packaging_worker;
        GRANT SELECT, INSERT ON workflow.conversion_message_inbox,
            workflow.conversion_accepted_result, workflow.conversion_late_result
            TO folio_packaging_worker;
        CREATE POLICY audiobook_conversion_packaging_worker_policy
            ON workflow.audiobook_conversion TO folio_packaging_worker USING (true);
        CREATE POLICY conversion_stage_run_packaging_worker_policy
            ON workflow.conversion_stage_run TO folio_packaging_worker USING (true) WITH CHECK (true);
        CREATE POLICY conversion_accepted_result_packaging_worker_policy
            ON workflow.conversion_accepted_result TO folio_packaging_worker USING (true) WITH CHECK (true);
    END IF;
END;
$$;
