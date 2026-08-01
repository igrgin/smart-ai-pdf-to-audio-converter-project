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
