CREATE SCHEMA IF NOT EXISTS trust_operations;

CREATE TABLE trust_operations.operations_case (
    case_id UUID PRIMARY KEY,
    case_type VARCHAR(40) NOT NULL CHECK (case_type IN (
        'SUPPORT', 'EXPIRING_ACCESS', 'FAILED_STAGE', 'ENTITLEMENT_INTERVENTION',
        'VOICE_AVAILABILITY', 'SERVICE_INCIDENT'
    )),
    required_role VARCHAR(40) NOT NULL CHECK (required_role IN (
        'SUPPORT', 'RELIABILITY', 'ENTITLEMENT', 'VOICE', 'INCIDENT_RESPONDER'
    )),
    listener_id UUID REFERENCES public.listener_identity(listener_id),
    resource_kind VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    opaque_resource_reference UUID NOT NULL UNIQUE,
    restriction_code VARCHAR(80) NOT NULL,
    consequence_code VARCHAR(80) NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    safety_priority INTEGER NOT NULL CHECK (safety_priority >= 0),
    urgency INTEGER NOT NULL CHECK (urgency >= 0),
    allowed_actions VARCHAR(64)[] NOT NULL CHECK (cardinality(allowed_actions) > 0),
    correlation_id VARCHAR(160) NOT NULL UNIQUE,
    resolved_at TIMESTAMPTZ
);

CREATE INDEX operations_case_queue_idx
    ON trust_operations.operations_case (required_role, safety_priority DESC, deadline, urgency DESC)
    WHERE resolved_at IS NULL;

CREATE TABLE trust_operations.delegated_access_request (
    request_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES trust_operations.operations_case(case_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    staff_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    staff_display_name VARCHAR(200) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    allowed_actions VARCHAR(64)[] NOT NULL CHECK (cardinality(allowed_actions) > 0),
    expires_at TIMESTAMPTZ NOT NULL,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    requested_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > requested_at AND expires_at <= requested_at + INTERVAL '24 hours')
);

CREATE TABLE trust_operations.delegated_access_request_decision (
    request_id UUID PRIMARY KEY REFERENCES trust_operations.delegated_access_request(request_id),
    grant_id UUID NOT NULL UNIQUE,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    decided_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE trust_operations.listener_notification (
    notification_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    case_id UUID NOT NULL REFERENCES trust_operations.operations_case(case_id),
    grant_id UUID,
    event_type VARCHAR(64) NOT NULL CHECK (event_type IN (
        'DELEGATED_ACCESS_APPROVED', 'DELEGATED_ACCESS_REVOKED', 'EMERGENCY_ACCESS_STARTED',
        'PRIVILEGED_ACTION_PERFORMED'
    )),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE trust_operations.delegated_access_grant (
    grant_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES trust_operations.operations_case(case_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    staff_id UUID NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    allowed_actions VARCHAR(64)[] NOT NULL CHECK (cardinality(allowed_actions) > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (
        expires_at > valid_from AND expires_at <= valid_from + INTERVAL '24 hours'
    ),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    notification_id UUID NOT NULL UNIQUE REFERENCES trust_operations.listener_notification(notification_id),
    UNIQUE (grant_id, listener_id)
);

ALTER TABLE trust_operations.delegated_access_request_decision
    ADD CONSTRAINT delegated_access_request_decision_grant_fk
    FOREIGN KEY (grant_id) REFERENCES trust_operations.delegated_access_grant(grant_id);

CREATE TABLE trust_operations.delegated_access_revocation (
    grant_id UUID PRIMARY KEY REFERENCES trust_operations.delegated_access_grant(grant_id),
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    notification_id UUID NOT NULL UNIQUE REFERENCES trust_operations.listener_notification(notification_id),
    revoked_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE trust_operations.privileged_action_audit (
    event_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES trust_operations.operations_case(case_id),
    grant_id UUID,
    actor_id UUID NOT NULL,
    actor_reference UUID NOT NULL,
    authority VARCHAR(64) NOT NULL,
    target_reference UUID NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    policy_code VARCHAR(80) NOT NULL,
    action_code VARCHAR(80) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    correlation_id VARCHAR(200) NOT NULL UNIQUE,
    notification_id UUID REFERENCES trust_operations.listener_notification(notification_id),
    review_obligation VARCHAR(80),
    appeal_obligation VARCHAR(80)
);

CREATE TABLE trust_operations.emergency_access_grant (
    grant_id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES trust_operations.operations_case(case_id),
    listener_id UUID REFERENCES public.listener_identity(listener_id),
    responder_id UUID NOT NULL,
    incident_reference VARCHAR(120) NOT NULL,
    justification_code VARCHAR(120) NOT NULL,
    purpose_code VARCHAR(80) NOT NULL,
    allowed_actions VARCHAR(64)[] NOT NULL CHECK (cardinality(allowed_actions) > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (
        expires_at > valid_from AND expires_at <= valid_from + INTERVAL '30 minutes'
    ),
    review_due_at TIMESTAMPTZ NOT NULL CHECK (
        review_due_at > valid_from AND review_due_at <= valid_from + INTERVAL '24 hours'
    ),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    request_fingerprint CHAR(64) NOT NULL,
    notification_id UUID UNIQUE REFERENCES trust_operations.listener_notification(notification_id)
);

CREATE TABLE trust_operations.emergency_access_review (
    grant_id UUID PRIMARY KEY REFERENCES trust_operations.emergency_access_grant(grant_id),
    responder_id UUID NOT NULL,
    reviewer_id UUID NOT NULL CHECK (reviewer_id <> responder_id),
    outcome VARCHAR(40) NOT NULL CHECK (outcome IN ('APPROPRIATE', 'POLICY_GAP', 'UNJUSTIFIED')),
    review_code VARCHAR(120) NOT NULL,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    reviewed_at TIMESTAMPTZ NOT NULL
);

CREATE FUNCTION trust_operations.reject_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Trust Operations history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER listener_notification_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.listener_notification
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER delegated_access_request_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.delegated_access_request
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER delegated_access_request_decision_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.delegated_access_request_decision
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER delegated_access_grant_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.delegated_access_grant
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER delegated_access_revocation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.delegated_access_revocation
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER privileged_action_audit_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.privileged_action_audit
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER emergency_access_grant_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.emergency_access_grant
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
CREATE TRIGGER emergency_access_review_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON trust_operations.emergency_access_review
    FOR EACH STATEMENT EXECUTE FUNCTION trust_operations.reject_history_mutation();
