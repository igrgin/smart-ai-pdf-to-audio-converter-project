ALTER TABLE workflow.inspection_work
    ADD CONSTRAINT inspection_work_result_identity
        UNIQUE (work_id, submission_id, listener_id, operation_key);

ALTER TABLE admission.inspection_result
    ADD CONSTRAINT inspection_result_matches_work
        FOREIGN KEY (work_id, submission_id, listener_id, operation_key)
        REFERENCES workflow.inspection_work (work_id, submission_id, listener_id, operation_key);

CREATE FUNCTION workflow.pending_inspections(p_available_at TIMESTAMPTZ, p_limit INTEGER)
RETURNS TABLE (work_id UUID, operation_key VARCHAR)
LANGUAGE sql
SECURITY DEFINER
SET search_path = workflow, admission, pg_temp
AS $$
    SELECT work.work_id, work.operation_key
    FROM workflow.inspection_work work
    WHERE work.state <> 'COMPLETED'
      AND (work.lease_expires_at IS NULL OR work.lease_expires_at <= p_available_at)
      AND EXISTS (SELECT 1 FROM workflow.inspection_inbox inbox WHERE inbox.work_id = work.work_id)
      AND p_limit BETWEEN 1 AND 100
    ORDER BY work.created_at, work.work_id
    LIMIT p_limit
$$;

CREATE FUNCTION workflow.claim_inspection(
    p_work_id UUID,
    p_worker_id VARCHAR,
    p_lease_until TIMESTAMPTZ,
    p_operation_key VARCHAR,
    p_maximum_attempts INTEGER
)
RETURNS TABLE (submission_id UUID, claim_status VARCHAR)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = workflow, admission, pg_temp
AS $$
DECLARE
    claimed workflow.inspection_work%ROWTYPE;
    bounded_lease_until TIMESTAMPTZ;
BEGIN
    IF p_lease_until <= CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'Inspection lease is outside the allowed runtime';
    END IF;
    bounded_lease_until := LEAST(p_lease_until, CURRENT_TIMESTAMP + INTERVAL '9 minutes');
    IF p_maximum_attempts < 1 OR p_maximum_attempts > 3 THEN
        RAISE EXCEPTION 'Inspection retry limit is outside the allowed range';
    END IF;

    SELECT * INTO STRICT claimed
    FROM workflow.inspection_work
    WHERE work_id = p_work_id
    FOR UPDATE;

    IF claimed.operation_key <> p_operation_key THEN
        RAISE EXCEPTION 'Inspection operation does not match durable work';
    END IF;
    IF claimed.state = 'COMPLETED' THEN
        RETURN QUERY SELECT NULL::UUID, 'COMPLETED'::VARCHAR;
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM workflow.inspection_inbox WHERE work_id = p_work_id) THEN
        RAISE EXCEPTION 'Inspection delivery has not been accepted';
    END IF;
    IF claimed.lease_expires_at > CURRENT_TIMESTAMP AND claimed.lease_owner <> p_worker_id THEN
        RETURN QUERY SELECT NULL::UUID, 'LEASED_BY_ANOTHER_WORKER'::VARCHAR;
        RETURN;
    END IF;
    IF claimed.attempt_count >= p_maximum_attempts THEN
        UPDATE workflow.inspection_work
        SET state = 'LEASED', lease_owner = p_worker_id, lease_expires_at = bounded_lease_until
        WHERE work_id = p_work_id;
        RETURN QUERY SELECT claimed.submission_id, 'RETRIES_EXHAUSTED'::VARCHAR;
        RETURN;
    END IF;

    UPDATE workflow.inspection_work
    SET state = 'LEASED',
        lease_owner = p_worker_id,
        lease_expires_at = bounded_lease_until,
        attempt_count = attempt_count + 1
    WHERE work_id = p_work_id;
    RETURN QUERY SELECT claimed.submission_id, 'CLAIMED'::VARCHAR;
END
$$;

CREATE FUNCTION admission.inspection_subject(p_work_id UUID, p_worker_id VARCHAR)
RETURNS TABLE (submission_id UUID, listener_id UUID, declared_media_type VARCHAR)
LANGUAGE sql
SECURITY DEFINER
SET search_path = admission, workflow, pg_temp
AS $$
    SELECT submission.submission_id, submission.listener_id, submission.declared_media_type
    FROM workflow.inspection_work work
    JOIN admission.publication_submission submission ON submission.submission_id = work.submission_id
    WHERE work.work_id = p_work_id
      AND work.state = 'LEASED'
      AND work.lease_owner = p_worker_id
      AND work.lease_expires_at > CURRENT_TIMESTAMP
$$;

CREATE FUNCTION admission.record_inspection_result(
    p_result_id UUID,
    p_work_id UUID,
    p_worker_id VARCHAR,
    p_operation_key VARCHAR,
    p_outcome VARCHAR,
    p_reason_code VARCHAR,
    p_media_type VARCHAR,
    p_toolchain_version VARCHAR,
    p_created_at TIMESTAMPTZ
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = admission, workflow, pg_temp
AS $$
DECLARE
    work workflow.inspection_work%ROWTYPE;
BEGIN
    SELECT * INTO STRICT work
    FROM workflow.inspection_work
    WHERE work_id = p_work_id
    FOR UPDATE;
    IF work.state = 'COMPLETED' THEN
        RETURN FALSE;
    END IF;
    IF work.lease_owner <> p_worker_id
            OR work.operation_key <> p_operation_key
            OR work.lease_expires_at IS NULL
            OR work.lease_expires_at <= CURRENT_TIMESTAMP THEN
        RAISE EXCEPTION 'Inspection lease was lost';
    END IF;
    INSERT INTO admission.inspection_result (
        result_id, work_id, listener_id, submission_id, operation_key, outcome,
        reason_code, media_type, toolchain_version, created_at
    ) VALUES (
        p_result_id, p_work_id, work.listener_id, work.submission_id, p_operation_key, p_outcome,
        p_reason_code, p_media_type, p_toolchain_version, p_created_at
    );
    UPDATE workflow.inspection_work
    SET state = 'COMPLETED', lease_expires_at = NULL, completed_at = p_created_at
    WHERE work_id = p_work_id;
    RETURN TRUE;
END
$$;

CREATE FUNCTION admission.load_inspection_result(p_work_id UUID, p_worker_id VARCHAR)
RETURNS TABLE (submission_id UUID, outcome VARCHAR, reason_code VARCHAR)
LANGUAGE sql
SECURITY DEFINER
SET search_path = admission, workflow, pg_temp
AS $$
    SELECT result.submission_id, result.outcome, result.reason_code
    FROM admission.inspection_result result
    JOIN workflow.inspection_work work ON work.work_id = result.work_id
    WHERE result.work_id = p_work_id AND work.lease_owner = p_worker_id
$$;

REVOKE ALL ON FUNCTION workflow.pending_inspections(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION workflow.claim_inspection(UUID, VARCHAR, TIMESTAMPTZ, VARCHAR, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION admission.inspection_subject(UUID, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION admission.record_inspection_result(
    UUID, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TIMESTAMPTZ
) FROM PUBLIC;
REVOKE ALL ON FUNCTION admission.load_inspection_result(UUID, VARCHAR) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audiobook_inspection') THEN
        GRANT USAGE ON SCHEMA admission, workflow TO audiobook_inspection;
        GRANT EXECUTE ON FUNCTION workflow.pending_inspections(TIMESTAMPTZ, INTEGER) TO audiobook_inspection;
        GRANT EXECUTE ON FUNCTION workflow.claim_inspection(
            UUID, VARCHAR, TIMESTAMPTZ, VARCHAR, INTEGER
        ) TO audiobook_inspection;
        GRANT EXECUTE ON FUNCTION admission.inspection_subject(UUID, VARCHAR) TO audiobook_inspection;
        GRANT EXECUTE ON FUNCTION admission.record_inspection_result(
            UUID, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TIMESTAMPTZ
        ) TO audiobook_inspection;
        GRANT EXECUTE ON FUNCTION admission.load_inspection_result(UUID, VARCHAR) TO audiobook_inspection;
    END IF;
END
$$;
