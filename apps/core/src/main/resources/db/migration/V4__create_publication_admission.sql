CREATE TABLE rights_attestation (
    attestation_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    terms_version VARCHAR(80) NOT NULL,
    notice_version VARCHAR(80) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE publication_submission (
    submission_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    attestation_id UUID NOT NULL UNIQUE REFERENCES rights_attestation(attestation_id),
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
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX publication_submission_listener_idx ON publication_submission(listener_id, created_at);

CREATE TABLE submission_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    operation_type VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    submission_id UUID NOT NULL REFERENCES publication_submission(submission_id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE upload_session (
    session_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES publication_submission(submission_id),
    capability_hash CHAR(64) NOT NULL UNIQUE,
    next_offset BIGINT NOT NULL DEFAULT 0 CHECK (next_offset >= 0),
    storage_generation VARCHAR(200),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE upload_chunk (
    submission_id UUID NOT NULL REFERENCES publication_submission(submission_id),
    chunk_offset BIGINT NOT NULL,
    byte_length INTEGER NOT NULL CHECK (byte_length > 0),
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (submission_id, chunk_offset)
);

CREATE TABLE quarantine_object (
    object_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES publication_submission(submission_id),
    object_key VARCHAR(200) NOT NULL UNIQUE,
    storage_generation VARCHAR(200) NOT NULL,
    byte_length BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    cleanup_due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inspection_work (
    work_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES publication_submission(submission_id),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'LEASED', 'COMPLETED')),
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE admission_outbox (
    message_id UUID PRIMARY KEY,
    message_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id UUID NOT NULL,
    work_id UUID NOT NULL REFERENCES inspection_work(work_id),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE TABLE inspection_inbox (
    message_id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES inspection_work(work_id),
    accepted_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inspection_result (
    result_id UUID PRIMARY KEY,
    work_id UUID NOT NULL UNIQUE REFERENCES inspection_work(work_id),
    submission_id UUID NOT NULL UNIQUE REFERENCES publication_submission(submission_id),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('ADMITTED', 'REJECTED')),
    reason_code VARCHAR(64),
    media_type VARCHAR(80),
    toolchain_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE source_publication (
    source_publication_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    submission_id UUID NOT NULL UNIQUE REFERENCES publication_submission(submission_id),
    media_type VARCHAR(80) NOT NULL,
    byte_length BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE audiobook_conversion (
    conversion_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    source_publication_id UUID NOT NULL UNIQUE REFERENCES source_publication(source_publication_id),
    state VARCHAR(32) NOT NULL CHECK (state IN ('PREPARING')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX audiobook_conversion_listener_idx ON audiobook_conversion(listener_id, created_at);

CREATE TABLE cleanup_obligation (
    obligation_id UUID PRIMARY KEY,
    submission_id UUID NOT NULL UNIQUE REFERENCES publication_submission(submission_id),
    object_id UUID REFERENCES quarantine_object(object_id),
    reason_code VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'COMPLETED')),
    due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE admission_audit_event (
    event_id UUID PRIMARY KEY,
    listener_id UUID REFERENCES listener_identity(listener_id),
    submission_id UUID,
    event_type VARCHAR(64) NOT NULL,
    decision VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION reject_admission_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Admission history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rights_attestation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON rights_attestation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admission_history_mutation();

CREATE TRIGGER inspection_inbox_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON inspection_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admission_history_mutation();

CREATE TRIGGER inspection_result_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON inspection_result
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admission_history_mutation();

CREATE TRIGGER admission_audit_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON admission_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admission_history_mutation();
