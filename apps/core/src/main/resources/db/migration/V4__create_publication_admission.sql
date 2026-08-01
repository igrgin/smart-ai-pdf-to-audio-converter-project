CREATE SCHEMA IF NOT EXISTS admission;
CREATE SCHEMA IF NOT EXISTS workflow;
SET search_path TO admission, workflow, public;

CREATE TABLE admission.rights_attestation (
    attestation_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    terms_version VARCHAR(80) NOT NULL,
    notice_version VARCHAR(80) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    UNIQUE (attestation_id, listener_id)
);

CREATE TABLE admission.publication_submission (
    submission_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    attestation_id UUID NOT NULL UNIQUE,
    entitlement_reservation_id UUID NOT NULL UNIQUE,
    planned_conversion_id UUID NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'AWAITING_UPLOAD', 'UPLOADED', 'INSPECTING', 'ADMITTED', 'REJECTED', 'EXPIRED', 'CANCELLED'
    )),
    declared_media_type VARCHAR(80) NOT NULL,
    declared_byte_length BIGINT NOT NULL CHECK (declared_byte_length > 0),
    declared_sha256 CHAR(64) NOT NULL,
    reason_code VARCHAR(64),
    upload_expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (submission_id, listener_id),
    FOREIGN KEY (attestation_id, listener_id)
        REFERENCES admission.rights_attestation(attestation_id, listener_id)
);

CREATE INDEX publication_submission_listener_idx ON admission.publication_submission(listener_id, created_at);

CREATE TABLE admission.submission_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    operation_type VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    submission_id UUID NOT NULL REFERENCES admission.publication_submission(submission_id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admission.upload_session (
    session_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES admission.publication_submission(submission_id),
    capability_hash CHAR(64) NOT NULL UNIQUE,
    next_offset BIGINT NOT NULL DEFAULT 0 CHECK (next_offset >= 0),
    storage_generation VARCHAR(200),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admission.upload_chunk (
    submission_id UUID NOT NULL REFERENCES admission.publication_submission(submission_id),
    chunk_offset BIGINT NOT NULL,
    byte_length INTEGER NOT NULL CHECK (byte_length > 0),
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (submission_id, chunk_offset)
);

CREATE TABLE admission.quarantine_object (
    object_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    submission_id UUID NOT NULL UNIQUE,
    object_key VARCHAR(200) NOT NULL UNIQUE,
    storage_generation VARCHAR(200) NOT NULL,
    byte_length BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    cleanup_due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (submission_id, listener_id)
        REFERENCES admission.publication_submission(submission_id, listener_id)
);

CREATE TABLE workflow.inspection_work (
    work_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    submission_id UUID NOT NULL UNIQUE,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'LEASED', 'COMPLETED')),
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (submission_id, listener_id)
        REFERENCES admission.publication_submission(submission_id, listener_id)
);

CREATE TABLE workflow.admission_outbox (
    message_id UUID PRIMARY KEY,
    message_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id UUID NOT NULL,
    work_id UUID NOT NULL REFERENCES workflow.inspection_work(work_id),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE TABLE workflow.inspection_inbox (
    message_id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES workflow.inspection_work(work_id),
    accepted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admission.inspection_result (
    result_id UUID PRIMARY KEY,
    work_id UUID NOT NULL UNIQUE REFERENCES workflow.inspection_work(work_id),
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    submission_id UUID NOT NULL UNIQUE,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('ADMITTED', 'REJECTED')),
    reason_code VARCHAR(64),
    media_type VARCHAR(80),
    toolchain_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (submission_id, listener_id)
        REFERENCES admission.publication_submission(submission_id, listener_id)
);

CREATE TABLE admission.source_publication (
    source_publication_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    submission_id UUID NOT NULL UNIQUE,
    media_type VARCHAR(80) NOT NULL,
    byte_length BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (source_publication_id, listener_id),
    FOREIGN KEY (submission_id, listener_id)
        REFERENCES admission.publication_submission(submission_id, listener_id)
);

CREATE TABLE workflow.audiobook_conversion (
    conversion_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    source_publication_id UUID NOT NULL UNIQUE,
    state VARCHAR(32) NOT NULL CHECK (state IN ('PREPARING')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (source_publication_id, listener_id)
        REFERENCES admission.source_publication(source_publication_id, listener_id)
);

CREATE INDEX audiobook_conversion_listener_idx ON workflow.audiobook_conversion(listener_id, created_at);

CREATE TABLE admission.cleanup_obligation (
    obligation_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES admission.publication_submission(submission_id),
    object_id UUID REFERENCES admission.quarantine_object(object_id),
    reason_code VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'COMPLETED')),
    due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admission.admission_audit_event (
    event_id UUID PRIMARY KEY,
    listener_id UUID REFERENCES listener_identity(listener_id),
    submission_id UUID,
    event_type VARCHAR(64) NOT NULL,
    decision VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION admission.reject_admission_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Admission history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rights_attestation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON admission.rights_attestation
    FOR EACH STATEMENT EXECUTE FUNCTION admission.reject_admission_history_mutation();

CREATE TRIGGER inspection_inbox_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.inspection_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION admission.reject_admission_history_mutation();

CREATE TRIGGER inspection_result_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON admission.inspection_result
    FOR EACH STATEMENT EXECUTE FUNCTION admission.reject_admission_history_mutation();

CREATE TRIGGER admission_audit_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON admission.admission_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION admission.reject_admission_history_mutation();

ALTER TABLE admission.rights_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission.publication_submission ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission.quarantine_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission.inspection_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission.source_publication ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.inspection_work ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.audiobook_conversion ENABLE ROW LEVEL SECURITY;

CREATE POLICY rights_attestation_listener_policy ON admission.rights_attestation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY publication_submission_listener_policy ON admission.publication_submission
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY quarantine_object_listener_policy ON admission.quarantine_object
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY inspection_result_listener_policy ON admission.inspection_result
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY source_publication_listener_policy ON admission.source_publication
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY inspection_work_listener_policy ON workflow.inspection_work
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY audiobook_conversion_listener_policy ON workflow.audiobook_conversion
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
