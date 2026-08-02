CREATE SCHEMA IF NOT EXISTS retention;

ALTER TABLE narration.provider_capability_profile
    ADD COLUMN erasure_strategy VARCHAR(32) NOT NULL DEFAULT 'NON_RETENTION_CONTRACT'
        CHECK (erasure_strategy IN ('NON_RETENTION_CONTRACT', 'PROVIDER_DELETE_REQUIRED')),
    ADD COLUMN erasure_evidence_type VARCHAR(32) NOT NULL DEFAULT 'DATA_POLICY_VERSION'
        CHECK (erasure_evidence_type IN ('DATA_POLICY_VERSION', 'PROVIDER_DELETE_RECEIPT'));

ALTER TABLE listener_identity DROP CONSTRAINT listener_identity_access_state_check;
ALTER TABLE listener_identity
    ADD CONSTRAINT listener_identity_access_state_check
    CHECK (access_state IN ('ACTIVE', 'BANNED', 'DELETED'));

CREATE TABLE retention.deletion_request (
    request_id UUID PRIMARY KEY,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('AUDIOBOOK', 'ACCOUNT')),
    subject_digest CHAR(64) NOT NULL,
    resource_digest CHAR(64),
    operation_key VARCHAR(200) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'ACCEPTED', 'ERASING', 'LIVE_ERASED', 'COMPLETED', 'FAILED'
    )),
    requested_at TIMESTAMPTZ NOT NULL,
    quick_erasure_due_at TIMESTAMPTZ NOT NULL,
    live_erasure_due_at TIMESTAMPTZ NOT NULL,
    provider_evidence_due_at TIMESTAMPTZ NOT NULL,
    backup_expires_at TIMESTAMPTZ NOT NULL,
    evidence_expires_at TIMESTAMPTZ NOT NULL,
    live_erased_at TIMESTAMPTZ,
    provider_evidenced_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    UNIQUE (subject_digest, operation_key),
    CHECK ((scope = 'ACCOUNT') = (resource_digest IS NULL)),
    CHECK (quick_erasure_due_at <= requested_at + INTERVAL '24 hours'),
    CHECK (live_erasure_due_at <= requested_at + INTERVAL '23 days'),
    CHECK (provider_evidence_due_at <= requested_at + INTERVAL '30 days'),
    CHECK (backup_expires_at <= requested_at + INTERVAL '90 days'),
    CHECK (evidence_expires_at >= backup_expires_at)
);

CREATE TABLE retention.deletion_tombstone (
    tombstone_id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES retention.deletion_request(request_id),
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('AUDIOBOOK', 'ACCOUNT')),
    subject_digest CHAR(64) NOT NULL,
    resource_digest CHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((scope = 'ACCOUNT') = (resource_digest IS NULL))
);

CREATE UNIQUE INDEX deletion_tombstone_account_unique
    ON retention.deletion_tombstone(subject_digest)
    WHERE scope = 'ACCOUNT';
CREATE UNIQUE INDEX deletion_tombstone_audiobook_unique
    ON retention.deletion_tombstone(subject_digest, resource_digest)
    WHERE scope = 'AUDIOBOOK';

CREATE TABLE retention.external_identity_tombstone (
    identity_digest CHAR(64) PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES retention.deletion_request(request_id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE retention.erasure_obligation (
    obligation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES retention.deletion_request(request_id),
    category VARCHAR(24) NOT NULL CHECK (category IN (
        'WORKING_ASSET', 'FINAL_ASSET', 'PROVIDER', 'RELATIONAL'
    )),
    asset_kind VARCHAR(40) NOT NULL CHECK (asset_kind IN (
        'QUARANTINE_OBJECT', 'NARRATION_PLAN', 'NARRATION_REVIEW',
        'AUDIO_WORKING', 'AUDIO_FINAL', 'PROVIDER_EVIDENCE',
        'RELATIONAL_PRIVATE_DATA'
    )),
    locator VARCHAR(600),
    locator_digest CHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'PENDING', 'ERASING', 'COMPLETED', 'FAILED'
    )),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    due_at TIMESTAMPTZ NOT NULL,
    hard_due_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    evidence_code VARCHAR(80),
    failure_code VARCHAR(80),
    UNIQUE (request_id, asset_kind, locator_digest),
    CHECK ((state = 'COMPLETED') = (completed_at IS NOT NULL)),
    CHECK (state <> 'COMPLETED' OR locator IS NULL)
);

CREATE INDEX erasure_obligation_pending_idx
    ON retention.erasure_obligation(state, due_at, created_at)
    WHERE state IN ('PENDING', 'FAILED');

CREATE TABLE retention.erasure_evidence (
    evidence_id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES retention.deletion_request(request_id),
    evidence_type VARCHAR(64) NOT NULL,
    evidence_code VARCHAR(80) NOT NULL,
    item_count INTEGER NOT NULL CHECK (item_count >= 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (request_id, evidence_type)
);

CREATE TABLE retention.compliance_incident (
    incident_id UUID PRIMARY KEY,
    request_id UUID REFERENCES retention.deletion_request(request_id),
    incident_code VARCHAR(80) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    UNIQUE NULLS NOT DISTINCT (request_id, incident_code)
);

CREATE FUNCTION retention.reject_tombstone_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Deletion tombstones are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER deletion_tombstone_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON retention.deletion_tombstone
    FOR EACH STATEMENT EXECUTE FUNCTION retention.reject_tombstone_mutation();
CREATE TRIGGER external_identity_tombstone_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON retention.external_identity_tombstone
    FOR EACH STATEMENT EXECUTE FUNCTION retention.reject_tombstone_mutation();

CREATE FUNCTION retention.erasure_authorized() RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM retention.deletion_request request
        WHERE request.request_id::text = current_setting('app.erasure_request_id', true)
          AND request.state = 'ERASING'
    )
$$;

CREATE OR REPLACE FUNCTION generation.reject_immutable_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'Finalized generation records are immutable';
END;
$$ LANGUAGE plpgsql;

-- Append-only business history remains immutable during normal operation. The narrowly
-- scoped erasure transaction may remove rows that contain the deleted listener's private
-- content or authorization sources after their content-free deletion evidence is recorded.
CREATE OR REPLACE FUNCTION provider.reject_operation_evidence_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'Provider operation evidence is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION narration.reject_generation_recipe_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'Narration Generation Recipe history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION admission.reject_admission_history_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'Admission history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION workflow.reject_workflow_evidence_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'Conversion workflow evidence is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION public.reject_entitlement_history_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'Conversion Entitlement history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trust_operations.reject_history_mutation() RETURNS trigger AS $$
BEGIN
    IF retention.erasure_authorized() THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'Trust Operations history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION retention.require_erasure_target(
    p_listener_id UUID,
    p_conversion_id UUID
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    active_request UUID := NULLIF(current_setting('app.erasure_request_id', true), '')::uuid;
BEGIN
    IF active_request IS NULL OR NOT EXISTS (
        SELECT 1
        FROM retention.deletion_request request
        JOIN retention.erasure_obligation obligation
          ON obligation.request_id = request.request_id
        WHERE request.request_id = active_request
          AND request.state = 'ERASING'
          AND obligation.category = 'RELATIONAL'
          AND obligation.asset_kind = 'RELATIONAL_PRIVATE_DATA'
          AND obligation.state = 'ERASING'
          AND (
              obligation.locator = 'ACCOUNT' || chr(10) || p_listener_id::text
              OR (
                  p_conversion_id IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM library.private_audiobook audiobook
                      WHERE audiobook.listener_id = p_listener_id
                        AND audiobook.conversion_id = p_conversion_id
                        AND obligation.locator = 'AUDIOBOOK' || chr(10)
                            || p_listener_id::text || chr(10) || audiobook.audiobook_id::text
                  )
              )
          )
    ) THEN
        RAISE EXCEPTION 'Erasure target is not authorized by the active obligation';
    END IF;
END;
$$;

CREATE FUNCTION retention.erase_conversion_private_data(
    p_listener_id UUID,
    p_conversion_id UUID
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    audiobook UUID;
    submission UUID;
    attestation UUID;
BEGIN
    PERFORM retention.require_erasure_target(p_listener_id, p_conversion_id);
    SELECT private_audiobook.audiobook_id INTO audiobook
    FROM library.private_audiobook
    WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;

    IF audiobook IS NOT NULL THEN
        DELETE FROM offline_access.authorization_operation
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
        DELETE FROM offline_access.authorization_generation
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
        DELETE FROM library.playback_position_operation
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
        DELETE FROM library.playback_position
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
        UPDATE library.private_audiobook
        SET current_asset_version_id = NULL, availability = 'ERASED'
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
        DELETE FROM library.final_asset_part
        WHERE listener_id = p_listener_id
          AND asset_version_id IN (
              SELECT asset_version_id FROM library.audiobook_asset_version
              WHERE listener_id = p_listener_id AND audiobook_id = audiobook
          );
        DELETE FROM library.audiobook_chapter
        WHERE listener_id = p_listener_id
          AND asset_version_id IN (
              SELECT asset_version_id FROM library.audiobook_asset_version
              WHERE listener_id = p_listener_id AND audiobook_id = audiobook
          );
        DELETE FROM library.audiobook_asset_version
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
        DELETE FROM library.private_audiobook
        WHERE listener_id = p_listener_id AND audiobook_id = audiobook;
    END IF;

    DELETE FROM provider.operation_evidence
    WHERE generation_recipe_id IN (
        SELECT recipe_id FROM narration.generation_recipe
        WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id
    );
    DELETE FROM generation.active_segment_manifest WHERE conversion_id = p_conversion_id;
    DELETE FROM generation.accepted_segment WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM generation.speech_attempt WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM generation.speech_segment WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM generation.audiobook_chapter_plan WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM generation.packaged_audiobook_result WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM generation.working_asset_erasure_obligation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM generation.segment_manifest WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;

    DELETE FROM narration.narration_review_operation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM narration.narration_review_decision WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM narration.recipe_confirmation_operation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;

    DELETE FROM workflow.conversion_message_inbox WHERE conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_accepted_result WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_repair_operation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_pause_event WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_resume_operation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_cancellation_operation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_terminal_failure_operation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_cleanup_obligation WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_provider_cost_entry WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_late_result WHERE conversion_id = p_conversion_id;
    DELETE FROM workflow.conversion_stage_run WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;

    DELETE FROM workflow.narration_plan_delivery
    WHERE work_id IN (SELECT work_id FROM workflow.narration_plan_work WHERE conversion_id = p_conversion_id);
    DELETE FROM workflow.narration_plan_inbox
    WHERE work_id IN (SELECT work_id FROM workflow.narration_plan_work WHERE conversion_id = p_conversion_id);
    DELETE FROM workflow.narration_plan_outbox
    WHERE work_id IN (SELECT work_id FROM workflow.narration_plan_work WHERE conversion_id = p_conversion_id);
    DELETE FROM workflow.narration_plan_resume_operation
    WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM narration.narration_plan WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM workflow.narration_plan_work WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    UPDATE workflow.audiobook_conversion SET current_generation_recipe_id = NULL
    WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM narration.generation_recipe WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;

    DELETE FROM public.character_entitlement_ledger_entry
    WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    DELETE FROM public.provider_spend_ledger_entry
    WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;

    SELECT publication.submission_id, publication.attestation_id
    INTO submission, attestation
    FROM admission.source_publication source
    JOIN admission.publication_submission publication
      ON publication.submission_id = source.submission_id
    JOIN workflow.audiobook_conversion conversion
      ON conversion.source_publication_id = source.source_publication_id
    WHERE conversion.listener_id = p_listener_id AND conversion.conversion_id = p_conversion_id;

    DELETE FROM workflow.audiobook_conversion
    WHERE listener_id = p_listener_id AND conversion_id = p_conversion_id;
    IF submission IS NOT NULL THEN
        DELETE FROM admission.admission_audit_event WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM workflow.inspection_inbox
        WHERE work_id IN (SELECT work_id FROM workflow.inspection_work WHERE submission_id = submission);
        DELETE FROM workflow.admission_outbox
        WHERE work_id IN (SELECT work_id FROM workflow.inspection_work WHERE submission_id = submission);
        DELETE FROM admission.inspection_result WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM workflow.inspection_work WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.cleanup_obligation WHERE submission_id = submission;
        DELETE FROM admission.quarantine_object WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.upload_chunk WHERE submission_id = submission;
        DELETE FROM admission.upload_session WHERE submission_id = submission;
        DELETE FROM admission.submission_operation WHERE submission_id = submission;
        DELETE FROM admission.source_publication WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.publication_submission WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.rights_attestation
        WHERE listener_id = p_listener_id AND attestation_id = attestation
          AND NOT EXISTS (SELECT 1 FROM admission.publication_submission WHERE attestation_id = attestation);
    END IF;
END;
$$;

CREATE FUNCTION retention.erase_account_private_data(p_listener_id UUID) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    conversion UUID;
    submission UUID;
    attestation UUID;
BEGIN
    PERFORM retention.require_erasure_target(p_listener_id, NULL);
    FOR conversion IN
        SELECT conversion_id FROM workflow.audiobook_conversion WHERE listener_id = p_listener_id
    LOOP
        PERFORM retention.erase_conversion_private_data(p_listener_id, conversion);
    END LOOP;

    FOR submission, attestation IN
        SELECT submission_id, attestation_id
        FROM admission.publication_submission WHERE listener_id = p_listener_id
    LOOP
        DELETE FROM admission.admission_audit_event WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM workflow.inspection_inbox
        WHERE work_id IN (SELECT work_id FROM workflow.inspection_work WHERE submission_id = submission);
        DELETE FROM workflow.admission_outbox
        WHERE work_id IN (SELECT work_id FROM workflow.inspection_work WHERE submission_id = submission);
        DELETE FROM admission.inspection_result WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM workflow.inspection_work WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.cleanup_obligation WHERE submission_id = submission;
        DELETE FROM admission.quarantine_object WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.upload_chunk WHERE submission_id = submission;
        DELETE FROM admission.upload_session WHERE submission_id = submission;
        DELETE FROM admission.submission_operation WHERE submission_id = submission;
        DELETE FROM admission.source_publication WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.publication_submission WHERE listener_id = p_listener_id AND submission_id = submission;
        DELETE FROM admission.rights_attestation
        WHERE listener_id = p_listener_id AND attestation_id = attestation
          AND NOT EXISTS (SELECT 1 FROM admission.publication_submission WHERE attestation_id = attestation);
    END LOOP;

    DELETE FROM trust_operations.emergency_access_review
    WHERE grant_id IN (SELECT grant_id FROM trust_operations.emergency_access_grant WHERE listener_id = p_listener_id);
    DELETE FROM trust_operations.privileged_action_audit
    WHERE case_id IN (SELECT case_id FROM trust_operations.operations_case WHERE listener_id = p_listener_id);
    DELETE FROM trust_operations.delegated_access_request_decision
    WHERE request_id IN (SELECT request_id FROM trust_operations.delegated_access_request WHERE listener_id = p_listener_id);
    DELETE FROM trust_operations.delegated_access_revocation WHERE listener_id = p_listener_id;
    DELETE FROM trust_operations.delegated_access_grant WHERE listener_id = p_listener_id;
    DELETE FROM trust_operations.delegated_access_request WHERE listener_id = p_listener_id;
    DELETE FROM trust_operations.emergency_access_grant WHERE listener_id = p_listener_id;
    DELETE FROM trust_operations.listener_notification WHERE listener_id = p_listener_id;
    DELETE FROM trust_operations.operations_case WHERE listener_id = p_listener_id;

    DELETE FROM public.stripe_demonstration_event_inbox inbox
    WHERE EXISTS (
        SELECT 1 FROM public.demonstration_subscription subscription
        WHERE subscription.listener_id = p_listener_id
          AND inbox.payload::text LIKE '%' || subscription.stripe_customer_id || '%'
    );
    DELETE FROM public.demonstration_subscription_grant_adjustment
    WHERE stripe_invoice_id IN (
        SELECT stripe_invoice_id FROM public.demonstration_subscription_invoice_grant invoice
        JOIN public.demonstration_subscription subscription
          ON subscription.stripe_subscription_id = invoice.stripe_subscription_id
        WHERE subscription.listener_id = p_listener_id
    );
    DELETE FROM public.demonstration_subscription_invoice_grant
    WHERE stripe_subscription_id IN (
        SELECT stripe_subscription_id FROM public.demonstration_subscription WHERE listener_id = p_listener_id
    );
    DELETE FROM public.demonstration_subscription WHERE listener_id = p_listener_id;
    DELETE FROM public.character_entitlement_ledger_entry WHERE listener_id = p_listener_id;
    DELETE FROM public.provider_spend_ledger_entry WHERE listener_id = p_listener_id;
    DELETE FROM public.conversion_entitlement_grant WHERE listener_id = p_listener_id;
    DELETE FROM public.free_conversion_grant WHERE listener_id = p_listener_id;
    DELETE FROM public.entitlement_audit_event WHERE listener_id = p_listener_id;

    DELETE FROM admission.admission_audit_event WHERE listener_id = p_listener_id;
    DELETE FROM external_identity_link WHERE listener_id = p_listener_id;
    DELETE FROM spring_session WHERE principal_name = p_listener_id::text;
END;
$$;

CREATE FUNCTION retention.erase_audiobook_private_data(
    p_listener_id UUID,
    p_audiobook_id UUID
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    conversion UUID;
BEGIN
    SELECT conversion_id INTO conversion
    FROM library.private_audiobook
    WHERE listener_id = p_listener_id AND audiobook_id = p_audiobook_id;
    IF conversion IS NOT NULL THEN
        PERFORM retention.erase_conversion_private_data(p_listener_id, conversion);
    END IF;
END;
$$;

CREATE FUNCTION retention.complete_erasure_obligation(
    p_obligation_id UUID,
    p_evidence_code VARCHAR
) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    active_request UUID := NULLIF(current_setting('app.erasure_request_id', true), '')::uuid;
BEGIN
    UPDATE retention.erasure_obligation
    SET state = 'COMPLETED', locator = NULL, completed_at = CURRENT_TIMESTAMP,
        evidence_code = p_evidence_code, failure_code = NULL
    FROM retention.deletion_request request
    WHERE retention.erasure_obligation.obligation_id = p_obligation_id
      AND retention.erasure_obligation.request_id = active_request
      AND retention.erasure_obligation.state = 'ERASING'
      AND request.request_id = active_request
      AND request.state = 'ERASING'
      AND (
          (retention.erasure_obligation.asset_kind = 'AUDIO_WORKING'
              AND p_evidence_code = 'WORKING_ASSET_DELETED')
          OR (retention.erasure_obligation.asset_kind = 'AUDIO_FINAL'
              AND p_evidence_code = 'FINAL_ASSET_DELETED')
          OR (retention.erasure_obligation.asset_kind = 'NARRATION_PLAN'
              AND p_evidence_code = 'NARRATION_PLAN_DELETED')
          OR (retention.erasure_obligation.asset_kind = 'NARRATION_REVIEW'
              AND p_evidence_code = 'NARRATION_REVIEW_DELETED')
          OR (retention.erasure_obligation.asset_kind = 'QUARANTINE_OBJECT'
              AND p_evidence_code = 'QUARANTINE_OBJECT_DELETED')
          OR (retention.erasure_obligation.asset_kind = 'PROVIDER_EVIDENCE'
              AND p_evidence_code = 'PROVIDER_NON_RETENTION_EVIDENCED')
          OR (retention.erasure_obligation.asset_kind = 'RELATIONAL_PRIVATE_DATA'
              AND p_evidence_code = 'PRIVATE_RELATIONAL_DATA_DELETED')
      );
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Erasure completion is not authorized for the active obligation';
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION retention.require_erasure_target(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION retention.erase_conversion_private_data(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION retention.erase_account_private_data(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION retention.erase_audiobook_private_data(UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION retention.complete_erasure_obligation(UUID, VARCHAR) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'folio_erasure_worker') THEN
        REVOKE cloudsqlsuperuser FROM folio_erasure_worker;
        GRANT USAGE ON SCHEMA retention, provider, narration, library TO folio_erasure_worker;
        GRANT SELECT ON retention.deletion_request, retention.erasure_obligation
            TO folio_erasure_worker;
        GRANT UPDATE (state, failure_code, live_erased_at, provider_evidenced_at, completed_at)
            ON retention.deletion_request TO folio_erasure_worker;
        GRANT UPDATE (state, attempt_count, failure_code)
            ON retention.erasure_obligation TO folio_erasure_worker;
        GRANT SELECT, INSERT ON retention.erasure_evidence TO folio_erasure_worker;
        GRANT SELECT, INSERT, UPDATE ON retention.compliance_incident TO folio_erasure_worker;
        GRANT SELECT ON provider.operation_evidence,
            narration.provider_capability_profile
            TO folio_erasure_worker;
        GRANT EXECUTE ON FUNCTION retention.erase_conversion_private_data(UUID, UUID),
            retention.erase_account_private_data(UUID),
            retention.erase_audiobook_private_data(UUID, UUID),
            retention.complete_erasure_obligation(UUID, VARCHAR)
            TO folio_erasure_worker;
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION offline_access.advance_audiobook_authorization_generation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF ((OLD.availability = 'AVAILABLE' AND NEW.availability <> 'AVAILABLE')
        OR OLD.current_asset_version_id IS DISTINCT FROM NEW.current_asset_version_id)
       AND EXISTS (
           SELECT 1 FROM public.listener_identity listener
           WHERE listener.listener_id = NEW.listener_id
             AND listener.access_state = 'ACTIVE'
       ) THEN
        UPDATE offline_access.authorization_generation
        SET generation = generation + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE listener_id = NEW.listener_id
          AND audiobook_id = NEW.audiobook_id;
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE trust_operations.operations_case
    DROP CONSTRAINT operations_case_case_type_check;
ALTER TABLE trust_operations.operations_case
    ADD CONSTRAINT operations_case_case_type_check CHECK (case_type IN (
        'SUPPORT', 'EXPIRING_ACCESS', 'FAILED_STAGE', 'ENTITLEMENT_INTERVENTION',
        'VOICE_AVAILABILITY', 'SERVICE_INCIDENT', 'COMPLIANCE_INCIDENT'
    ));
ALTER TABLE trust_operations.operations_case
    DROP CONSTRAINT operations_case_required_role_check;
ALTER TABLE trust_operations.operations_case
    ADD CONSTRAINT operations_case_required_role_check CHECK (required_role IN (
        'SUPPORT', 'RELIABILITY', 'ENTITLEMENT', 'VOICE', 'INCIDENT_RESPONDER',
        'SECURITY_REVIEWER'
    ));
