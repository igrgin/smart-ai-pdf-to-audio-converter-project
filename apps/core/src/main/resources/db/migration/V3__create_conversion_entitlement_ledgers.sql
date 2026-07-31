CREATE TABLE free_conversion_grant (
    grant_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL UNIQUE REFERENCES listener_identity(listener_id),
    approval_reference VARCHAR(200) NOT NULL UNIQUE,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    granted_characters BIGINT NOT NULL CHECK (granted_characters > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL CHECK (valid_until > valid_from),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE character_entitlement_ledger_entry (
    entry_id UUID PRIMARY KEY,
    grant_id UUID NOT NULL REFERENCES free_conversion_grant(grant_id),
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    conversion_id UUID,
    reservation_id UUID,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN (
        'GRANT', 'RESERVATION', 'SETTLEMENT', 'CORRECTION', 'EXPIRY'
    )),
    available_delta BIGINT NOT NULL,
    reserved_delta BIGINT NOT NULL,
    committed_delta BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX character_entitlement_listener_idx
    ON character_entitlement_ledger_entry(listener_id);

CREATE UNIQUE INDEX character_entitlement_conversion_reservation_idx
    ON character_entitlement_ledger_entry(conversion_id)
    WHERE entry_type = 'RESERVATION';

CREATE TABLE provider_spend_ledger_entry (
    entry_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    conversion_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    provider VARCHAR(80) NOT NULL,
    generation_recipe_reference VARCHAR(200) NOT NULL,
    rate_card_version VARCHAR(200) NOT NULL,
    operation_key VARCHAR(200) NOT NULL UNIQUE,
    entry_type VARCHAR(32) NOT NULL CHECK (entry_type IN ('RESERVATION', 'SETTLEMENT')),
    reserved_delta BIGINT NOT NULL,
    committed_delta BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX provider_spend_provider_idx ON provider_spend_ledger_entry(provider);
CREATE INDEX provider_spend_listener_idx ON provider_spend_ledger_entry(listener_id);
CREATE UNIQUE INDEX provider_spend_conversion_reservation_idx
    ON provider_spend_ledger_entry(conversion_id)
    WHERE entry_type = 'RESERVATION';

CREATE TABLE entitlement_operation (
    operation_key VARCHAR(200) PRIMARY KEY,
    operation_type VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    related_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE entitlement_audit_event (
    event_id UUID PRIMARY KEY,
    listener_id UUID REFERENCES listener_identity(listener_id),
    conversion_id UUID,
    reservation_id UUID,
    event_type VARCHAR(48) NOT NULL,
    decision VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE entitlement_transaction_lock (
    lock_id SMALLINT PRIMARY KEY CHECK (lock_id = 1)
);

INSERT INTO entitlement_transaction_lock(lock_id) VALUES (1);

CREATE FUNCTION reject_entitlement_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Conversion Entitlement history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER free_conversion_grant_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON free_conversion_grant
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER character_entitlement_ledger_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON character_entitlement_ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER provider_spend_ledger_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON provider_spend_ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER entitlement_operation_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON entitlement_operation
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER entitlement_audit_event_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON entitlement_audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();
