CREATE TABLE narration.narration_review_decision (
    decision_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    conversion_id UUID NOT NULL UNIQUE,
    action VARCHAR(24) NOT NULL CHECK (action IN ('APPROVE', 'SKIP_OPTIONAL')),
    schema_version VARCHAR(80) NOT NULL CHECK (schema_version = 'narration-review-v1'),
    working_asset_ref VARCHAR(240) NOT NULL UNIQUE,
    asset_sha256 CHAR(64) NOT NULL,
    section_count INTEGER NOT NULL CHECK (section_count > 0),
    review_item_count INTEGER NOT NULL CHECK (review_item_count >= 0),
    source_version BIGINT NOT NULL CHECK (source_version >= 0),
    result_version BIGINT NOT NULL CHECK (result_version = source_version + 1),
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TABLE narration.narration_review_operation (
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    operation_key VARCHAR(200) NOT NULL,
    conversion_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    decision_id UUID NOT NULL UNIQUE REFERENCES narration.narration_review_decision(decision_id),
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (listener_id, operation_key),
    FOREIGN KEY (conversion_id, listener_id)
        REFERENCES workflow.audiobook_conversion(conversion_id, listener_id)
);

CREATE TRIGGER narration_review_decision_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON narration.narration_review_decision
    FOR EACH STATEMENT EXECUTE FUNCTION narration.reject_generation_recipe_mutation();

CREATE TRIGGER narration_review_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON narration.narration_review_operation
    FOR EACH STATEMENT EXECUTE FUNCTION narration.reject_generation_recipe_mutation();

ALTER TABLE narration.narration_review_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE narration.narration_review_operation ENABLE ROW LEVEL SECURITY;

CREATE POLICY narration_review_decision_listener_policy ON narration.narration_review_decision
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
CREATE POLICY narration_review_operation_listener_policy ON narration.narration_review_operation
    USING (listener_id = NULLIF(current_setting('app.listener_id', true), '')::uuid);
