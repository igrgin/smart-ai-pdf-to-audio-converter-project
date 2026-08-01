-- V9 freezes narration reviews before this entitlement projection is introduced.
CREATE TABLE conversion_entitlement_grant (
    grant_id UUID PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    grant_kind VARCHAR(40) NOT NULL CHECK (grant_kind IN ('FREE', 'DEMONSTRATION_SUBSCRIPTION')),
    evidence_reference VARCHAR(200) NOT NULL UNIQUE,
    granted_characters BIGINT NOT NULL CHECK (granted_characters > 0),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL CHECK (valid_until > valid_from),
    created_at TIMESTAMPTZ NOT NULL
);

INSERT INTO conversion_entitlement_grant (
    grant_id, listener_id, grant_kind, evidence_reference,
    granted_characters, valid_from, valid_until, created_at
)
SELECT grant_id, listener_id, 'FREE', approval_reference,
       granted_characters, valid_from, valid_until, created_at
FROM free_conversion_grant;

ALTER TABLE character_entitlement_ledger_entry
    DROP CONSTRAINT character_entitlement_ledger_entry_grant_id_fkey;
ALTER TABLE character_entitlement_ledger_entry
    ADD CONSTRAINT character_entitlement_ledger_entry_grant_id_fkey
    FOREIGN KEY (grant_id) REFERENCES conversion_entitlement_grant(grant_id);

ALTER TABLE character_entitlement_ledger_entry
    DROP CONSTRAINT character_entitlement_ledger_entry_entry_type_check;
ALTER TABLE character_entitlement_ledger_entry
    ADD CONSTRAINT character_entitlement_ledger_entry_entry_type_check
    CHECK (entry_type IN ('GRANT', 'RESERVATION', 'SETTLEMENT', 'CORRECTION', 'EXPIRY', 'REFUND', 'VOID'));

CREATE INDEX conversion_entitlement_grant_listener_period_idx
    ON conversion_entitlement_grant(listener_id, valid_from, valid_until);

CREATE TABLE demonstration_subscription (
    stripe_subscription_id VARCHAR(200) PRIMARY KEY,
    listener_id UUID NOT NULL REFERENCES listener_identity(listener_id),
    stripe_customer_id VARCHAR(200) NOT NULL,
    subscription_status VARCHAR(32) NOT NULL CHECK (subscription_status IN (
        'ACTIVE', 'CANCEL_AT_PERIOD_END', 'CANCELED', 'PAST_DUE', 'UNPAID'
    )),
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    latest_event_created TIMESTAMPTZ NOT NULL,
    latest_event_id VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX demonstration_subscription_listener_idx
    ON demonstration_subscription(listener_id);

CREATE TABLE demonstration_subscription_invoice_grant (
    stripe_invoice_id VARCHAR(200) PRIMARY KEY,
    stripe_subscription_id VARCHAR(200) NOT NULL REFERENCES demonstration_subscription(stripe_subscription_id),
    grant_id UUID NOT NULL UNIQUE REFERENCES conversion_entitlement_grant(grant_id),
    stripe_payment_intent_id VARCHAR(200),
    stripe_charge_id VARCHAR(200),
    stripe_event_id VARCHAR(200) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX demonstration_invoice_payment_intent_idx
    ON demonstration_subscription_invoice_grant(stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;
CREATE INDEX demonstration_invoice_charge_idx
    ON demonstration_subscription_invoice_grant(stripe_charge_id)
    WHERE stripe_charge_id IS NOT NULL;

CREATE TABLE demonstration_subscription_grant_adjustment (
    adjustment_reference VARCHAR(200) PRIMARY KEY,
    stripe_invoice_id VARCHAR(200) NOT NULL REFERENCES demonstration_subscription_invoice_grant(stripe_invoice_id),
    grant_id UUID NOT NULL REFERENCES conversion_entitlement_grant(grant_id),
    adjustment_kind VARCHAR(24) NOT NULL CHECK (adjustment_kind IN ('REFUND', 'VOID')),
    stripe_event_id VARCHAR(200) NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE stripe_demonstration_event_inbox (
    event_id VARCHAR(200) PRIMARY KEY,
    event_type VARCHAR(200) NOT NULL,
    event_created TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    projection_status VARCHAR(16) NOT NULL CHECK (projection_status IN ('PENDING', 'PROJECTED', 'IGNORED')),
    received_at TIMESTAMPTZ NOT NULL,
    projected_at TIMESTAMPTZ
);

CREATE INDEX stripe_demonstration_event_pending_idx
    ON stripe_demonstration_event_inbox(event_created, event_id)
    WHERE projection_status = 'PENDING';

CREATE TABLE demonstration_subscription_projector_control (
    control_id SMALLINT PRIMARY KEY CHECK (control_id = 1),
    paused BOOLEAN NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);

INSERT INTO demonstration_subscription_projector_control(control_id, paused, changed_at)
VALUES (1, FALSE, CURRENT_TIMESTAMP);

CREATE FUNCTION protect_stripe_event_evidence() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Stripe event evidence is append-only';
    END IF;
    IF OLD.event_id IS DISTINCT FROM NEW.event_id
       OR OLD.event_type IS DISTINCT FROM NEW.event_type
       OR OLD.event_created IS DISTINCT FROM NEW.event_created
       OR OLD.payload IS DISTINCT FROM NEW.payload
       OR OLD.payload_sha256 IS DISTINCT FROM NEW.payload_sha256
       OR OLD.received_at IS DISTINCT FROM NEW.received_at THEN
        RAISE EXCEPTION 'Stripe event evidence is append-only';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER conversion_entitlement_grant_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON conversion_entitlement_grant
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER demonstration_invoice_grant_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON demonstration_subscription_invoice_grant
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER demonstration_grant_adjustment_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON demonstration_subscription_grant_adjustment
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();

CREATE TRIGGER stripe_event_evidence_immutable_on_update
    BEFORE UPDATE ON stripe_demonstration_event_inbox
    FOR EACH ROW EXECUTE FUNCTION protect_stripe_event_evidence();

CREATE TRIGGER stripe_event_evidence_append_only
    BEFORE DELETE OR TRUNCATE ON stripe_demonstration_event_inbox
    FOR EACH STATEMENT EXECUTE FUNCTION reject_entitlement_history_mutation();
