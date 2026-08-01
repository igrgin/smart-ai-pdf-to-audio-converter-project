ALTER TABLE workflow.narration_plan_work
    DROP CONSTRAINT narration_plan_work_state_check;
ALTER TABLE workflow.narration_plan_work
    ADD CONSTRAINT narration_plan_work_state_check
    CHECK (state IN ('READY', 'CLAIMED', 'SUCCEEDED', 'EXHAUSTED', 'PAUSED'));
ALTER TABLE workflow.narration_plan_work
    ADD COLUMN pause_reason_code VARCHAR(64),
    ADD COLUMN resume_from_page INTEGER CHECK (resume_from_page > 0),
    ADD COLUMN listener_guidance VARCHAR(240),
    ADD CONSTRAINT narration_plan_work_pause_details_check CHECK (
        (state = 'PAUSED'
            AND pause_reason_code IS NOT NULL
            AND resume_from_page IS NOT NULL
            AND listener_guidance IS NOT NULL)
        OR
        (state <> 'PAUSED'
            AND pause_reason_code IS NULL
            AND resume_from_page IS NULL
            AND listener_guidance IS NULL)
    );

ALTER TABLE workflow.audiobook_conversion
    DROP CONSTRAINT audiobook_conversion_state_check;
ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_state_check
    CHECK (state IN ('PREPARING', 'AWAITING_REVIEW', 'GENERATING', 'PAUSED'));

ALTER TABLE workflow.audiobook_conversion
    ADD CONSTRAINT audiobook_conversion_id_listener_unique UNIQUE (conversion_id, listener_id);

CREATE TABLE workflow.narration_plan_resume_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES public.listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

ALTER TABLE workflow.narration_plan_resume_operation ENABLE ROW LEVEL SECURITY;
CREATE POLICY narration_plan_resume_operation_listener_policy
    ON workflow.narration_plan_resume_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);

INSERT INTO workflow.narration_plan_work (
    work_id, listener_id, conversion_id, submission_id, operation_key, state, created_at
)
SELECT
    gen_random_uuid(), conversion.listener_id, conversion.conversion_id,
    publication.submission_id, 'narration-plan:' || conversion.conversion_id,
    'READY', conversion.created_at
FROM workflow.audiobook_conversion conversion
JOIN admission.source_publication publication
  ON publication.source_publication_id = conversion.source_publication_id
WHERE conversion.state = 'PREPARING'
  AND publication.media_type = 'application/pdf'
ON CONFLICT DO NOTHING;

INSERT INTO workflow.narration_plan_outbox (
    message_id, work_id, message_type, schema_version, created_at
)
SELECT gen_random_uuid(), work.work_id, 'PREPARE_NARRATION_PLAN', 1, work.created_at
FROM workflow.narration_plan_work work
LEFT JOIN workflow.narration_plan_outbox outbox ON outbox.work_id = work.work_id
WHERE outbox.work_id IS NULL;
