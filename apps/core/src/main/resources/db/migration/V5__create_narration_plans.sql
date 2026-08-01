CREATE SCHEMA IF NOT EXISTS narration;

ALTER TABLE workflow.audiobook_conversion
    DROP CONSTRAINT audiobook_conversion_state_check;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_state_check
    CHECK (state IN ('PREPARING', 'AWAITING_REVIEW'));
ALTER TABLE workflow.audiobook_conversion
    ADD COLUMN reason_code VARCHAR(64) NOT NULL DEFAULT 'NARRATION_PLAN_PENDING';
ALTER TABLE workflow.audiobook_conversion
    ALTER COLUMN reason_code DROP DEFAULT;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_owner_unique UNIQUE (conversion_id, listener_id);

CREATE TABLE narration.narration_plan (
    narration_plan_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    schema_version VARCHAR(80) NOT NULL,
    working_asset_ref VARCHAR(240) NOT NULL UNIQUE,
    asset_sha256 CHAR(64) NOT NULL,
    chapter_count INTEGER NOT NULL CHECK (chapter_count > 0),
    review_item_count INTEGER NOT NULL CHECK (review_item_count >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

ALTER TABLE narration.narration_plan ENABLE ROW LEVEL SECURITY;
CREATE POLICY narration_plan_listener_policy ON narration.narration_plan
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

CREATE TABLE workflow.narration_plan_work (
    work_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    submission_id UUID NOT NULL UNIQUE REFERENCES admission.publication_submission(submission_id),
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL CHECK (state IN ('READY', 'CLAIMED', 'SUCCEEDED', 'EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 4),
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE workflow.narration_plan_outbox (
    message_id UUID PRIMARY KEY,
    work_id UUID NOT NULL UNIQUE REFERENCES workflow.narration_plan_work(work_id),
    message_type VARCHAR(64) NOT NULL CHECK (message_type = 'PREPARE_NARRATION_PLAN'),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE TABLE workflow.narration_plan_inbox (
    message_id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES workflow.narration_plan_work(work_id),
    accepted_at TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER narration_plan_inbox_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON workflow.narration_plan_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION admission.reject_admission_history_mutation();

ALTER TABLE workflow.narration_plan_work ENABLE ROW LEVEL SECURITY;
CREATE POLICY narration_plan_work_listener_policy ON workflow.narration_plan_work
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_narration_worker') THEN
        REVOKE cloudsqlsuperuser FROM folio_narration_worker;
        GRANT USAGE ON SCHEMA workflow, narration TO folio_narration_worker;
        GRANT SELECT, UPDATE ON workflow.narration_plan_work TO folio_narration_worker;
        GRANT SELECT ON workflow.narration_plan_outbox TO folio_narration_worker;
        GRANT INSERT, SELECT ON workflow.narration_plan_inbox TO folio_narration_worker;
        GRANT SELECT, UPDATE ON workflow.audiobook_conversion TO folio_narration_worker;
        GRANT INSERT, SELECT ON narration.narration_plan TO folio_narration_worker;
        EXECUTE $policy$
            CREATE POLICY narration_plan_work_worker_policy ON workflow.narration_plan_work
                TO folio_narration_worker USING (true) WITH CHECK (true)
        $policy$;
        EXECUTE $policy$
            CREATE POLICY audiobook_conversion_narration_worker_policy ON workflow.audiobook_conversion
                TO folio_narration_worker
                USING (EXISTS (
                    SELECT 1 FROM workflow.narration_plan_work w
                    WHERE w.conversion_id = audiobook_conversion.conversion_id
                ))
        $policy$;
        EXECUTE $policy$
            CREATE POLICY narration_plan_worker_policy ON narration.narration_plan
                TO folio_narration_worker
                USING (EXISTS (
                    SELECT 1 FROM workflow.narration_plan_work w
                    WHERE w.conversion_id = narration_plan.conversion_id
                ))
                WITH CHECK (EXISTS (
                    SELECT 1 FROM workflow.narration_plan_work w
                    WHERE w.conversion_id = narration_plan.conversion_id
                ))
        $policy$;
    END IF;
END;
$$;
