package dev.audiobook.platform.workflow.persistence;

import dev.audiobook.platform.entitlement.ledger.service.ConversionEntitlementService;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.selection.service.NarrationSelectionService;
import dev.audiobook.platform.workflow.administration.service.ConversionWorkflowAdministrationService.*;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;
import dev.audiobook.platform.workflow.lifecycle.service.ConversionLifecycleService.*;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcConversionWorkflowPersistence {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final ConversionEntitlementService entitlementService;
    private final NarrationSelectionService narrationSelectionService;

    public StageView scheduleStage(
            UUID listenerId, UUID conversionId, Stage stage, int maximumAttempts) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(stage, "stage");
        if (maximumAttempts < 1 || maximumAttempts > 20) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 20");
        }
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_stage_run (
                    stage_run_id, listener_id, conversion_id, stage, state,
                    maximum_attempts, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'READY', ?, ?, ?)
                ON CONFLICT (conversion_id, stage) DO NOTHING
                """,
                identifierGenerator.generate(),
                listenerId,
                conversionId,
                stage.name(),
                maximumAttempts,
                timestamp(identityClock.instant()),
                timestamp(identityClock.instant()));
        return stage(listenerId, conversionId, stage);
    }

    public DeliveryDecision claimDelivery(WorkDelivery delivery) {
        validate(delivery);
        DeliveryDecision replay = existingDelivery(delivery.messageId());
        if (replay != null) {
            return new DeliveryDecision(
                    DeliveryDisposition.DUPLICATE, replay.stageRunId(), "MESSAGE_REPLAYED");
        }
        if (delivery.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            return recordDelivery(
                    delivery, DeliveryDisposition.DEAD_LETTERED, null, "UNKNOWN_SCHEMA");
        }

        ConversionPosition conversion = lockConversion(delivery.conversionId());
        if (conversion == null) {
            return recordDelivery(
                    delivery, DeliveryDisposition.DEAD_LETTERED, null, "UNKNOWN_CONVERSION");
        }
        if (conversion.terminal()) {
            return recordDelivery(delivery, DeliveryDisposition.LATE, null, "CONVERSION_TERMINAL");
        }
        if (conversion.version() != delivery.expectedConversionVersion()) {
            return recordDelivery(
                    delivery, DeliveryDisposition.STALE, null, "CONVERSION_VERSION_MISMATCH");
        }

        StageView stage = lockedStage(delivery.conversionId(), delivery.stage());
        if (stage == null) {
            return recordDelivery(
                    delivery, DeliveryDisposition.DEAD_LETTERED, null, "UNKNOWN_STAGE_RUN");
        }
        if (!prerequisiteAccepted(delivery.conversionId(), delivery.stage())) {
            return recordDelivery(
                    delivery,
                    DeliveryDisposition.REJECTED,
                    stage.stageRunId(),
                    "STAGE_PREREQUISITE_NOT_ACCEPTED");
        }
        Instant now = identityClock.instant();
        boolean expiredLease =
                stage.state() == StageState.CLAIMED
                        && stage.leaseExpiresAt() != null
                        && !stage.leaseExpiresAt().isAfter(now);
        if (stage.attemptCount() >= stage.maximumAttempts()) {
            return recordDelivery(
                    delivery,
                    DeliveryDisposition.REJECTED,
                    stage.stageRunId(),
                    "ATTEMPTS_EXHAUSTED");
        }
        if (stage.state() != StageState.READY && !expiredLease) {
            return recordDelivery(
                    delivery,
                    DeliveryDisposition.REJECTED,
                    stage.stageRunId(),
                    "STAGE_NOT_CLAIMABLE");
        }

        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'CLAIMED', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_message_id = ?, lease_expires_at = ?, updated_at = ?
                WHERE stage_run_id = ?
                """,
                delivery.workerId(),
                delivery.messageId(),
                timestamp(now.plus(delivery.leaseDuration())),
                timestamp(now),
                stage.stageRunId());
        return recordDelivery(delivery, DeliveryDisposition.CLAIMED, stage.stageRunId(), null);
    }

    private boolean prerequisiteAccepted(UUID conversionId, Stage stage) {
        Stage prerequisite =
                switch (stage) {
                    case ASSEMBLY -> Stage.SPEECH;
                    case PACKAGING -> Stage.ASSEMBLY;
                    case FINALIZATION -> Stage.PACKAGING;
                    default -> null;
                };
        if (prerequisite == null) {
            return true;
        }
        Integer accepted =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM workflow.conversion_stage_run run
                        WHERE run.conversion_id = ? AND run.stage = ? AND run.state = 'SUCCEEDED'
                          AND EXISTS (
                              SELECT 1 FROM workflow.conversion_accepted_result result
                              WHERE result.conversion_id = run.conversion_id AND result.stage = run.stage
                          )
                        """,
                        Integer.class,
                        conversionId,
                        prerequisite.name());
        return accepted != null && accepted == 1;
    }

    public boolean claimActive(UUID messageId, UUID conversionId, Stage stage) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(stage, "stage");
        Integer active =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM workflow.conversion_stage_run run
                        JOIN workflow.audiobook_conversion conversion
                          ON conversion.conversion_id = run.conversion_id
                        WHERE run.conversion_id = ? AND run.stage = ?
                          AND run.state = 'CLAIMED' AND run.lease_message_id = ?
                          AND run.lease_expires_at > ?
                          AND conversion.state NOT IN ('FINALIZED', 'FAILED', 'CANCELLED')
                        """,
                        Integer.class,
                        conversionId,
                        stage.name(),
                        messageId,
                        timestamp(identityClock.instant()));
        return active != null && active == 1;
    }

    public StageView checkpoint(StageCheckpoint checkpoint) {
        validate(checkpoint);
        StageLease lease = lockedStageLease(checkpoint.conversionId(), checkpoint.stage());
        if (lease == null
                || lease.state() != StageState.CLAIMED
                || !checkpoint.messageId().equals(lease.leaseMessageId())
                || lease.leaseExpiresAt() == null
                || !lease.leaseExpiresAt().isAfter(identityClock.instant())) {
            throw new IllegalStateException("Stage checkpoint lease is stale or unavailable");
        }
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET checkpoint_reference = ?, checkpoint_sha256 = ?, updated_at = ?
                WHERE stage_run_id = ?
                """,
                checkpoint.checkpointReference(),
                checkpoint.checkpointDigest(),
                timestamp(identityClock.instant()),
                lease.stageRunId());
        return stage(lease.listenerId(), checkpoint.conversionId(), checkpoint.stage());
    }

    public ResultDecision acceptResult(StageResult result) {
        validate(result);
        ConversionPosition conversion = lockConversion(result.conversionId());
        if (conversion == null) {
            return new ResultDecision(ResultDisposition.REJECTED, null, "UNKNOWN_CONVERSION");
        }
        if (conversion.terminal()) {
            recordLateResult(result, conversion.state());
            return new ResultDecision(ResultDisposition.LATE, null, "CONVERSION_TERMINAL");
        }
        AcceptedResultReplay replay =
                findAcceptedResult(result.conversionId(), result.operationKey());
        if (replay != null) {
            if (!replay.acceptedResult().resultDigest().equals(result.resultDigest())) {
                return new ResultDecision(
                        ResultDisposition.AMBIGUOUS,
                        replay.acceptedResult().acceptedResultId(),
                        "RESULT_DIGEST_MISMATCH");
            }
            if (!replay.matches(result)) {
                return new ResultDecision(
                        ResultDisposition.AMBIGUOUS,
                        replay.acceptedResult().acceptedResultId(),
                        "RESULT_IDENTITY_MISMATCH");
            }
            StageLease replayLease = lockedStageLease(result.conversionId(), result.stage());
            if (replayLease != null
                    && replay.stageRunId().equals(replayLease.stageRunId())
                    && replayLease.state() == StageState.CLAIMED
                    && result.messageId().equals(replayLease.leaseMessageId())
                    && replayLease.leaseExpiresAt() != null
                    && replayLease.leaseExpiresAt().isAfter(identityClock.instant())) {
                completeStage(replayLease.stageRunId());
            }
            return new ResultDecision(
                    ResultDisposition.REPLAYED, replay.acceptedResult().acceptedResultId(), null);
        }
        StageLease lease = lockedStageLease(result.conversionId(), result.stage());
        if (lease == null
                || lease.state() != StageState.CLAIMED
                || !result.messageId().equals(lease.leaseMessageId())
                || lease.leaseExpiresAt() == null
                || !lease.leaseExpiresAt().isAfter(identityClock.instant())) {
            return new ResultDecision(ResultDisposition.REJECTED, null, "RESULT_LEASE_INVALID");
        }
        UUID acceptedResultId = identifierGenerator.generate();
        Instant now = identityClock.instant();
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_accepted_result (
                    accepted_result_id, stage_run_id, listener_id, conversion_id, stage,
                    operation_key, result_reference, result_sha256, provider_work, accepted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                acceptedResultId,
                lease.stageRunId(),
                lease.listenerId(),
                result.conversionId(),
                result.stage().name(),
                result.operationKey(),
                result.resultReference(),
                result.resultDigest(),
                result.providerWork(),
                timestamp(now));
        completeStage(lease.stageRunId());
        return new ResultDecision(ResultDisposition.ACCEPTED, acceptedResultId, null);
    }

    public StageView failStage(StageFailure failure) {
        validate(failure);
        StageLease lease = lockedStageLease(failure.conversionId(), failure.stage());
        if (lease == null
                || lease.state() != StageState.CLAIMED
                || !failure.messageId().equals(lease.leaseMessageId())
                || lease.leaseExpiresAt() == null
                || !lease.leaseExpiresAt().isAfter(identityClock.instant())) {
            throw new IllegalStateException("Stage failure lease is stale or unavailable");
        }
        StageState nextState =
                failure.retryable() && lease.attemptCount() < lease.maximumAttempts()
                        ? StageState.READY
                        : StageState.FAILED;
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = ?, failure_code = ?, lease_owner = NULL, lease_message_id = NULL,
                    lease_expires_at = NULL, updated_at = ?
                WHERE stage_run_id = ?
                """,
                nextState.name(),
                failure.failureCode(),
                timestamp(identityClock.instant()),
                lease.stageRunId());
        return stage(lease.listenerId(), failure.conversionId(), failure.stage());
    }

    public StageView repairStage(
            UUID listenerId,
            UUID conversionId,
            Stage stage,
            long expectedConversionVersion,
            String idempotencyKey) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(stage, "stage");
        requireOperationKey(idempotencyKey);
        ConversionPosition conversion = lockConversion(conversionId);
        if (conversion == null
                || conversion.version() != expectedConversionVersion
                || conversion.terminal()) {
            throw new IllegalStateException("Conversion repair is stale or unavailable");
        }
        StageView current = lockedStage(conversionId, stage);
        Integer replay =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM workflow.conversion_repair_operation
                        WHERE operation_key = ? AND listener_id = ? AND conversion_id = ?
                          AND stage = ? AND expected_conversion_version = ?
                        """,
                        Integer.class,
                        idempotencyKey,
                        listenerId,
                        conversionId,
                        stage.name(),
                        expectedConversionVersion);
        if (replay != null && replay > 0) {
            return current;
        }
        int inserted =
                jdbcTemplate.update(
                        """
                        INSERT INTO workflow.conversion_repair_operation (
                            operation_key, listener_id, conversion_id, stage,
                            expected_conversion_version, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (operation_key) DO NOTHING
                        """,
                        idempotencyKey,
                        listenerId,
                        conversionId,
                        stage.name(),
                        expectedConversionVersion,
                        timestamp(identityClock.instant()));
        if (inserted == 0) {
            throw new IllegalArgumentException(
                    "Idempotency-Key was already used for another repair");
        }
        if (current == null
                || current.state() != StageState.FAILED
                || current.attemptCount() >= current.maximumAttempts()) {
            throw new IllegalStateException(
                    "Failed stage is not repairable within its retry bound");
        }
        Integer failedPrerequisites =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM workflow.conversion_stage_run prerequisite
                        WHERE prerequisite.listener_id = ? AND prerequisite.conversion_id = ?
                          AND prerequisite.state = 'FAILED'
                          AND CASE prerequisite.stage
                                WHEN 'INSPECTION' THEN 1 WHEN 'EXTRACTION' THEN 2
                                WHEN 'NARRATION_ANALYSIS' THEN 3 WHEN 'SPEECH' THEN 4
                                WHEN 'ASSEMBLY' THEN 5 WHEN 'PACKAGING' THEN 6
                                WHEN 'FINALIZATION' THEN 7
                              END < CASE ?
                                WHEN 'INSPECTION' THEN 1 WHEN 'EXTRACTION' THEN 2
                                WHEN 'NARRATION_ANALYSIS' THEN 3 WHEN 'SPEECH' THEN 4
                                WHEN 'ASSEMBLY' THEN 5 WHEN 'PACKAGING' THEN 6
                                WHEN 'FINALIZATION' THEN 7
                              END
                        """,
                        Integer.class,
                        listenerId,
                        conversionId,
                        stage.name());
        if (failedPrerequisites != null && failedPrerequisites > 0) {
            throw new IllegalStateException("An earlier failed stage must be repaired first");
        }
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'READY', failure_code = NULL, updated_at = ?
                WHERE stage_run_id = ? AND state = 'FAILED'
                """,
                timestamp(identityClock.instant()),
                current.stageRunId());
        return stage(listenerId, conversionId, stage);
    }

    private void completeStage(UUID stageRunId) {
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'SUCCEEDED', lease_owner = NULL, lease_message_id = NULL,
                    lease_expires_at = NULL, failure_code = NULL, updated_at = ?
                WHERE stage_run_id = ?
                """,
                timestamp(identityClock.instant()),
                stageRunId);
    }

    public PauseDetails pause(PauseCommand command) {
        validate(command);
        ConversionPosition conversion = lockConversion(command.conversionId());
        if (conversion == null || conversion.terminal()) {
            throw new IllegalStateException("Conversion cannot be paused");
        }
        StageLease lease = lockedStageLease(command.conversionId(), command.safeResumeStage());
        if (lease == null
                || !lease.listenerId().equals(command.listenerId())
                || lease.state() != StageState.CLAIMED
                || !command.messageId().equals(lease.leaseMessageId())
                || lease.leaseExpiresAt() == null
                || !lease.leaseExpiresAt().isAfter(identityClock.instant())) {
            throw new IllegalStateException("Conversion pause lease is stale or unavailable");
        }
        Instant now = identityClock.instant();
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'PAUSED', lease_owner = NULL, lease_message_id = NULL,
                    lease_expires_at = NULL, failure_code = ?, updated_at = ?
                WHERE stage_run_id = ?
                """,
                command.reasonCode(),
                timestamp(now),
                lease.stageRunId());
        jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'PAUSED', reason_code = ?, pause_responsible_party = ?,
                    safe_resume_stage = ?, pause_deadline = ?, version = version + 1
                WHERE conversion_id = ? AND listener_id = ? AND version = ?
                """,
                command.reasonCode(),
                command.responsibleParty().name(),
                command.safeResumeStage().name(),
                command.deadline() == null ? null : timestamp(command.deadline()),
                command.conversionId(),
                command.listenerId(),
                conversion.version());
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_pause_event (
                    pause_event_id, listener_id, conversion_id, reason_code,
                    responsible_party, safe_resume_stage, deadline, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                identifierGenerator.generate(),
                command.listenerId(),
                command.conversionId(),
                command.reasonCode(),
                command.responsibleParty().name(),
                command.safeResumeStage().name(),
                command.deadline() == null ? null : timestamp(command.deadline()),
                timestamp(now));
        return new PauseDetails(
                command.reasonCode(),
                command.responsibleParty(),
                command.safeResumeStage(),
                command.deadline());
    }

    public PauseDetails pauseDetails(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        ConversionPause pause = pausedConversion(listenerId, conversionId, false);
        if (pause == null) {
            return null;
        }
        return new PauseDetails(
                pause.reasonCode(),
                pause.responsibleParty(),
                pause.safeResumeStage(),
                pause.deadline());
    }

    public StageView resume(ResumeCommand command) {
        validate(command);
        ResumeOperation replay = resumeOperation(command.idempotencyKey());
        if (replay != null) {
            if (!replay.matches(command)) {
                throw new IllegalArgumentException(
                        "Idempotency-Key was already used for another resume");
            }
            return stage(command.listenerId(), command.conversionId(), replay.stage());
        }
        ConversionPause pause = lockPausedConversion(command.listenerId(), command.conversionId());
        if (pause == null || pause.version() != command.expectedConversionVersion()) {
            throw new IllegalStateException("Conversion resume is stale or unavailable");
        }
        ConversionEntitlementService.ResumeEligibility eligibility =
                entitlementService.resumeEligibility(command.listenerId(), command.conversionId());
        if (!eligibility.eligible()) {
            throw new IllegalStateException(
                    "Conversion resume policy rejected: " + eligibility.denialReason());
        }
        if (requiresRecipe(pause.safeResumeStage())) {
            narrationSelectionService.authorizeGeneration(
                    command.listenerId(), command.conversionId());
        }
        StageView safeStage = lockedStage(command.conversionId(), pause.safeResumeStage());
        if (safeStage == null || safeStage.state() != StageState.PAUSED) {
            throw new IllegalStateException("Safe resume stage is unavailable");
        }
        Instant now = identityClock.instant();
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_resume_operation (
                    operation_key, listener_id, conversion_id, expected_conversion_version,
                    safe_resume_stage, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                command.idempotencyKey(),
                command.listenerId(),
                command.conversionId(),
                command.expectedConversionVersion(),
                pause.safeResumeStage().name(),
                timestamp(now));
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'READY', failure_code = NULL, updated_at = ?
                WHERE stage_run_id = ? AND state = 'PAUSED'
                """,
                timestamp(now),
                safeStage.stageRunId());
        jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = ?, reason_code = ?, pause_responsible_party = NULL,
                    safe_resume_stage = NULL, pause_deadline = NULL, version = version + 1
                WHERE conversion_id = ? AND listener_id = ? AND state = 'PAUSED' AND version = ?
                """,
                resumeConversionState(pause.safeResumeStage()),
                resumeReasonCode(pause.safeResumeStage()),
                command.conversionId(),
                command.listenerId(),
                command.expectedConversionVersion());
        return stage(command.listenerId(), command.conversionId(), pause.safeResumeStage());
    }

    private CancellationResult cancelLocked(CancellationCommand command) {
        UUID reservationId = reservationId(command.listenerId(), command.conversionId());
        entitlementService.settle(
                new ConversionEntitlementService.SettlementRequest(
                        reservationId,
                        0,
                        command.incurredProviderCostMicros(),
                        "conversion-cancellation-settlement:" + command.conversionId()));
        Instant now = identityClock.instant();
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'CANCELLED', lease_owner = NULL, lease_message_id = NULL,
                    lease_expires_at = NULL, failure_code = 'LISTENER_CANCELLED', updated_at = ?
                WHERE conversion_id = ? AND state IN ('READY', 'CLAIMED', 'FAILED', 'PAUSED')
                """,
                timestamp(now),
                command.conversionId());
        jdbcTemplate.update(
                """
                DELETE FROM library.private_audiobook
                WHERE listener_id = ? AND conversion_id = ? AND current_asset_version_id IS NULL
                """,
                command.listenerId(),
                command.conversionId());
        int cancelled =
                jdbcTemplate.update(
                        """
                        UPDATE workflow.audiobook_conversion
                        SET state = 'CANCELLED', reason_code = 'LISTENER_CANCELLED',
                            pause_responsible_party = NULL, safe_resume_stage = NULL, pause_deadline = NULL,
                            version = version + 1
                        WHERE listener_id = ? AND conversion_id = ? AND version = ?
                        """,
                        command.listenerId(),
                        command.conversionId(),
                        command.expectedConversionVersion());
        if (cancelled != 1) {
            throw new IllegalStateException("Conversion cancellation transition was lost");
        }
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_cleanup_obligation (
                    obligation_id, listener_id, conversion_id, state, reason_code, scheduled_at
                ) VALUES (?, ?, ?, 'PENDING', 'LISTENER_CANCELLED', ?)
                ON CONFLICT (conversion_id) DO NOTHING
                """,
                identifierGenerator.generate(),
                command.listenerId(),
                command.conversionId(),
                timestamp(now));
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_cancellation_operation (
                    operation_key, listener_id, conversion_id, expected_conversion_version,
                    incurred_provider_cost_micros, request_reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                command.idempotencyKey(),
                command.listenerId(),
                command.conversionId(),
                command.expectedConversionVersion(),
                command.incurredProviderCostMicros(),
                command.requestReason(),
                timestamp(now));
        return cancellationResult(command.listenerId(), command.conversionId());
    }

    public CancellationResult cancelListener(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        requireOperationKey(idempotencyKey);
        CancellationOperation replay = cancellationOperation(idempotencyKey);
        if (replay != null) {
            if (!replay.matchesListener(listenerId, conversionId, expectedConversionVersion)) {
                throw new IllegalArgumentException(
                        "Idempotency-Key was already used for another cancellation");
            }
            return cancellationResult(listenerId, conversionId);
        }
        OwnedConversion conversion = lockOwnedConversion(listenerId, conversionId);
        if (conversion == null
                || conversion.version() != expectedConversionVersion
                || conversion.terminal()) {
            throw new IllegalStateException("Conversion cancellation is stale or unavailable");
        }
        long incurredCost = incurredProviderCost(listenerId, conversionId);
        return cancelLocked(
                new CancellationCommand(
                        listenerId,
                        conversionId,
                        expectedConversionVersion,
                        incurredCost,
                        "listener-requested",
                        idempotencyKey));
    }

    public void recordProviderCost(ProviderCost command) {
        validate(command);
        if (lockOwnedConversion(command.listenerId(), command.conversionId()) == null) {
            throw new IllegalStateException("Conversion provider cost owner is unavailable");
        }
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_provider_cost_entry (
                    entry_id, listener_id, conversion_id, incurred_provider_cost_micros,
                    evidence_reference, operation_key, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_key) DO NOTHING
                """,
                identifierGenerator.generate(),
                command.listenerId(),
                command.conversionId(),
                command.incurredProviderCostMicros(),
                command.evidenceReference(),
                command.operationKey(),
                timestamp(identityClock.instant()));
    }

    public CancellationResult failTerminal(TerminalFailureCommand command) {
        validate(command);
        TerminalFailureOperation replay = terminalFailureOperation(command.idempotencyKey());
        if (replay != null) {
            if (!replay.matches(command)) {
                throw new IllegalArgumentException(
                        "Idempotency-Key was already used for another terminal failure");
            }
            return cancellationResult(command.listenerId(), command.conversionId());
        }
        OwnedConversion conversion =
                lockOwnedConversion(command.listenerId(), command.conversionId());
        if (conversion == null
                || conversion.version() != command.expectedConversionVersion()
                || conversion.terminal()) {
            throw new IllegalStateException("Terminal conversion failure is stale or unavailable");
        }
        long reusableCharacters = reusableCharacters(command.listenerId(), command.conversionId());
        long incurredProviderCostMicros =
                incurredProviderCost(command.listenerId(), command.conversionId());
        entitlementService.settle(
                new ConversionEntitlementService.SettlementRequest(
                        reservationId(command.listenerId(), command.conversionId()),
                        reusableCharacters,
                        incurredProviderCostMicros,
                        "conversion-failure-settlement:" + command.conversionId()));
        Instant now = identityClock.instant();
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'FAILED', lease_owner = NULL, lease_message_id = NULL,
                    lease_expires_at = NULL, failure_code = ?, updated_at = ?
                WHERE conversion_id = ? AND state IN ('READY', 'CLAIMED', 'FAILED', 'PAUSED')
                """,
                command.failureCode(),
                timestamp(now),
                command.conversionId());
        jdbcTemplate.update(
                """
                DELETE FROM library.private_audiobook
                WHERE listener_id = ? AND conversion_id = ? AND current_asset_version_id IS NULL
                """,
                command.listenerId(),
                command.conversionId());
        int failed =
                jdbcTemplate.update(
                        """
                        UPDATE workflow.audiobook_conversion
                        SET state = 'FAILED', reason_code = ?, pause_responsible_party = NULL,
                            safe_resume_stage = NULL, pause_deadline = NULL, version = version + 1
                        WHERE listener_id = ? AND conversion_id = ? AND version = ?
                        """,
                        command.failureCode(),
                        command.listenerId(),
                        command.conversionId(),
                        command.expectedConversionVersion());
        if (failed != 1) {
            throw new IllegalStateException("Terminal conversion failure transition was lost");
        }
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_cleanup_obligation (
                    obligation_id, listener_id, conversion_id, state, reason_code, scheduled_at
                ) VALUES (?, ?, ?, 'PENDING', 'TERMINAL_FAILURE', ?)
                ON CONFLICT (conversion_id) DO NOTHING
                """,
                identifierGenerator.generate(),
                command.listenerId(),
                command.conversionId(),
                timestamp(now));
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_terminal_failure_operation (
                    operation_key, listener_id, conversion_id, expected_conversion_version,
                    failure_code, reusable_characters, incurred_provider_cost_micros, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                command.idempotencyKey(),
                command.listenerId(),
                command.conversionId(),
                command.expectedConversionVersion(),
                command.failureCode(),
                reusableCharacters,
                incurredProviderCostMicros,
                timestamp(now));
        return cancellationResult(command.listenerId(), command.conversionId());
    }

    private long incurredProviderCost(UUID listenerId, UUID conversionId) {
        Long incurredCost =
                jdbcTemplate.queryForObject(
                        """
                        WITH reservation AS (
                            SELECT characters.reserved_delta AS reserved_characters,
                                   provider.reserved_delta AS reserved_cost_micros
                            FROM character_entitlement_ledger_entry characters
                            JOIN provider_spend_ledger_entry provider
                              ON provider.reservation_id = characters.reservation_id
                            WHERE characters.listener_id = ? AND characters.conversion_id = ?
                              AND characters.entry_type = 'RESERVATION'
                              AND provider.entry_type = 'RESERVATION'
                        ), recorded AS (
                            SELECT COALESCE(SUM(incurred_provider_cost_micros), 0) AS cost_micros
                            FROM workflow.conversion_provider_cost_entry
                            WHERE listener_id = ? AND conversion_id = ?
                        ), calling AS (
                            SELECT COALESCE(SUM(GREATEST(1, CEIL(
                                reservation.reserved_cost_micros::numeric * segment.character_count
                                / reservation.reserved_characters
                            )::bigint)), 0) AS cost_micros
                            FROM generation.speech_attempt attempt
                            JOIN generation.speech_segment segment ON segment.segment_id = attempt.segment_id
                            CROSS JOIN reservation
                            WHERE attempt.listener_id = ? AND attempt.conversion_id = ?
                              AND attempt.state = 'CALLING_PROVIDER'
                              AND NOT EXISTS (
                                  SELECT 1 FROM workflow.conversion_provider_cost_entry cost
                                  WHERE cost.operation_key = 'provider-cost:' || attempt.attempt_id
                              )
                        )
                        SELECT LEAST(reservation.reserved_cost_micros,
                                    recorded.cost_micros + calling.cost_micros)
                        FROM reservation, recorded, calling
                        """,
                        Long.class,
                        listenerId,
                        conversionId,
                        listenerId,
                        conversionId,
                        listenerId,
                        conversionId);
        return incurredCost == null ? 0 : incurredCost;
    }

    private long reusableCharacters(UUID listenerId, UUID conversionId) {
        Long reusable =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COALESCE(SUM(segment.character_count), 0)
                        FROM generation.accepted_segment accepted_segment
                        JOIN generation.speech_segment segment
                          ON segment.segment_id = accepted_segment.segment_id
                        JOIN generation.active_segment_manifest active
                          ON active.manifest_id = segment.manifest_id
                         AND active.conversion_id = segment.conversion_id
                        WHERE accepted_segment.listener_id = ?
                          AND accepted_segment.conversion_id = ?
                          AND EXISTS (
                              SELECT 1 FROM workflow.conversion_accepted_result result
                              WHERE result.conversion_id = accepted_segment.conversion_id
                                AND result.stage = 'SPEECH' AND result.provider_work
                          )
                        """,
                        Long.class,
                        listenerId,
                        conversionId);
        return reusable == null ? 0 : reusable;
    }

    public CleanupObligation cleanup(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        List<CleanupObligation> matches =
                jdbcTemplate.query(
                        """
                        SELECT obligation_id, state, reason_code, scheduled_at
                        FROM workflow.conversion_cleanup_obligation
                        WHERE listener_id = ? AND conversion_id = ?
                        """,
                        (resultSet, row) ->
                                new CleanupObligation(
                                        resultSet.getObject("obligation_id", UUID.class),
                                        CleanupState.valueOf(resultSet.getString("state")),
                                        resultSet.getString("reason_code"),
                                        resultSet
                                                .getObject("scheduled_at", OffsetDateTime.class)
                                                .toInstant()),
                        listenerId,
                        conversionId);
        if (matches.isEmpty()) {
            throw new IllegalStateException("Conversion cleanup obligation is unavailable");
        }
        return matches.getFirst();
    }

    public AcceptedResult acceptedResult(UUID listenerId, UUID conversionId, String operationKey) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        requireOperationKey(operationKey);
        List<AcceptedResult> matches =
                jdbcTemplate.query(
                        """
                        SELECT accepted_result_id, stage, operation_key, result_reference,
                               result_sha256, provider_work, accepted_at
                        FROM workflow.conversion_accepted_result
                        WHERE listener_id = ? AND conversion_id = ? AND operation_key = ?
                        """,
                        (resultSet, row) -> acceptedResult(resultSet),
                        listenerId,
                        conversionId,
                        operationKey);
        if (matches.isEmpty()) {
            throw new IllegalStateException("Accepted conversion result is unavailable");
        }
        return matches.getFirst();
    }

    public StageView stage(UUID listenerId, UUID conversionId, Stage stage) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(stage, "stage");
        List<StageView> matches =
                jdbcTemplate.query(
                        """
                        SELECT stage_run_id, stage, state, attempt_count, maximum_attempts,
                               lease_owner, lease_expires_at, checkpoint_reference, checkpoint_sha256
                        FROM workflow.conversion_stage_run
                        WHERE listener_id = ? AND conversion_id = ? AND stage = ?
                        """,
                        (resultSet, row) -> stageView(resultSet),
                        listenerId,
                        conversionId,
                        stage.name());
        if (matches.isEmpty()) {
            throw new IllegalStateException("Conversion stage is unavailable");
        }
        return matches.getFirst();
    }

    private StageView lockedStage(UUID conversionId, Stage stage) {
        List<StageView> matches =
                jdbcTemplate.query(
                        """
                        SELECT stage_run_id, stage, state, attempt_count, maximum_attempts,
                               lease_owner, lease_expires_at, checkpoint_reference, checkpoint_sha256
                        FROM workflow.conversion_stage_run
                        WHERE conversion_id = ? AND stage = ?
                        FOR UPDATE
                        """,
                        (resultSet, row) -> stageView(resultSet),
                        conversionId,
                        stage.name());
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private StageLease lockedStageLease(UUID conversionId, Stage stage) {
        List<StageLease> matches =
                jdbcTemplate.query(
                        """
                        SELECT stage_run_id, listener_id, state, attempt_count, maximum_attempts,
                               lease_message_id, lease_expires_at
                        FROM workflow.conversion_stage_run
                        WHERE conversion_id = ? AND stage = ? FOR UPDATE
                        """,
                        (resultSet, row) -> {
                            OffsetDateTime expiresAt =
                                    resultSet.getObject("lease_expires_at", OffsetDateTime.class);
                            return new StageLease(
                                    resultSet.getObject("stage_run_id", UUID.class),
                                    resultSet.getObject("listener_id", UUID.class),
                                    StageState.valueOf(resultSet.getString("state")),
                                    resultSet.getInt("attempt_count"),
                                    resultSet.getInt("maximum_attempts"),
                                    resultSet.getObject("lease_message_id", UUID.class),
                                    expiresAt == null ? null : expiresAt.toInstant());
                        },
                        conversionId,
                        stage.name());
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static StageView stageView(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        OffsetDateTime leaseExpiresAt =
                resultSet.getObject("lease_expires_at", OffsetDateTime.class);
        return new StageView(
                resultSet.getObject("stage_run_id", UUID.class),
                Stage.valueOf(resultSet.getString("stage")),
                StageState.valueOf(resultSet.getString("state")),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("maximum_attempts"),
                resultSet.getString("lease_owner"),
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
                resultSet.getString("checkpoint_reference"),
                resultSet.getString("checkpoint_sha256"));
    }

    private ConversionPosition lockConversion(UUID conversionId) {
        List<ConversionPosition> matches =
                jdbcTemplate.query(
                        """
                        SELECT state, version FROM workflow.audiobook_conversion
                        WHERE conversion_id = ? FOR UPDATE
                        """,
                        (resultSet, row) ->
                                new ConversionPosition(
                                        resultSet.getString("state"), resultSet.getLong("version")),
                        conversionId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private ConversionPause lockPausedConversion(UUID listenerId, UUID conversionId) {
        return pausedConversion(listenerId, conversionId, true);
    }

    private ConversionPause pausedConversion(UUID listenerId, UUID conversionId, boolean lock) {
        List<ConversionPause> matches =
                jdbcTemplate.query(
                        ("""
                        SELECT version, reason_code, pause_responsible_party, safe_resume_stage, pause_deadline
                        FROM workflow.audiobook_conversion
                        WHERE listener_id = ? AND conversion_id = ? AND state = 'PAUSED'
                        """
                                + (lock ? " FOR UPDATE" : "")),
                        (resultSet, row) -> {
                            OffsetDateTime deadline =
                                    resultSet.getObject("pause_deadline", OffsetDateTime.class);
                            return new ConversionPause(
                                    resultSet.getLong("version"),
                                    resultSet.getString("reason_code"),
                                    ResponsibleParty.valueOf(
                                            resultSet.getString("pause_responsible_party")),
                                    Stage.valueOf(resultSet.getString("safe_resume_stage")),
                                    deadline == null ? null : deadline.toInstant());
                        },
                        listenerId,
                        conversionId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private ResumeOperation resumeOperation(String operationKey) {
        List<ResumeOperation> matches =
                jdbcTemplate.query(
                        """
                        SELECT listener_id, conversion_id, expected_conversion_version, safe_resume_stage
                        FROM workflow.conversion_resume_operation WHERE operation_key = ?
                        """,
                        (resultSet, row) ->
                                new ResumeOperation(
                                        resultSet.getObject("listener_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getLong("expected_conversion_version"),
                                        Stage.valueOf(resultSet.getString("safe_resume_stage"))),
                        operationKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private OwnedConversion lockOwnedConversion(UUID listenerId, UUID conversionId) {
        List<OwnedConversion> matches =
                jdbcTemplate.query(
                        """
                        SELECT state, version FROM workflow.audiobook_conversion
                        WHERE listener_id = ? AND conversion_id = ? FOR UPDATE
                        """,
                        (resultSet, row) ->
                                new OwnedConversion(
                                        resultSet.getString("state"), resultSet.getLong("version")),
                        listenerId,
                        conversionId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private UUID reservationId(UUID listenerId, UUID conversionId) {
        List<UUID> matches =
                jdbcTemplate.query(
                        """
                        SELECT submission.entitlement_reservation_id
                        FROM workflow.audiobook_conversion conversion
                        JOIN admission.source_publication publication
                          ON publication.source_publication_id = conversion.source_publication_id
                         AND publication.listener_id = conversion.listener_id
                        JOIN admission.publication_submission submission
                          ON submission.submission_id = publication.submission_id
                         AND submission.listener_id = conversion.listener_id
                        WHERE conversion.listener_id = ? AND conversion.conversion_id = ?
                        """,
                        (resultSet, row) ->
                                resultSet.getObject("entitlement_reservation_id", UUID.class),
                        listenerId,
                        conversionId);
        if (matches.isEmpty()) {
            throw new IllegalStateException("Conversion Entitlement reservation is unavailable");
        }
        return matches.getFirst();
    }

    private CancellationOperation cancellationOperation(String operationKey) {
        List<CancellationOperation> matches =
                jdbcTemplate.query(
                        """
                        SELECT listener_id, conversion_id, expected_conversion_version,
                               incurred_provider_cost_micros, request_reason
                        FROM workflow.conversion_cancellation_operation WHERE operation_key = ?
                        """,
                        (resultSet, row) ->
                                new CancellationOperation(
                                        resultSet.getObject("listener_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getLong("expected_conversion_version"),
                                        resultSet.getLong("incurred_provider_cost_micros"),
                                        resultSet.getString("request_reason")),
                        operationKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private TerminalFailureOperation terminalFailureOperation(String operationKey) {
        List<TerminalFailureOperation> matches =
                jdbcTemplate.query(
                        """
                        SELECT listener_id, conversion_id, expected_conversion_version,
                               failure_code, reusable_characters, incurred_provider_cost_micros
                        FROM workflow.conversion_terminal_failure_operation WHERE operation_key = ?
                        """,
                        (resultSet, row) ->
                                new TerminalFailureOperation(
                                        resultSet.getObject("listener_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getLong("expected_conversion_version"),
                                        resultSet.getString("failure_code"),
                                        resultSet.getLong("reusable_characters"),
                                        resultSet.getLong("incurred_provider_cost_micros")),
                        operationKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private CancellationResult cancellationResult(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT conversion_id, state, reason_code, version
                FROM workflow.audiobook_conversion WHERE listener_id = ? AND conversion_id = ?
                """,
                (resultSet, row) ->
                        new CancellationResult(
                                resultSet.getObject("conversion_id", UUID.class),
                                AudiobookConversionService.ConversionState.valueOf(
                                        resultSet.getString("state")),
                                resultSet.getString("reason_code"),
                                resultSet.getLong("version")),
                listenerId,
                conversionId);
    }

    private void recordLateResult(StageResult result, String terminalState) {
        jdbcTemplate.update(
                """
                INSERT INTO workflow.conversion_late_result (
                    late_result_id, message_id, conversion_id, stage, operation_key,
                    result_reference, result_sha256, provider_work, terminal_state, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id, operation_key) DO NOTHING
                """,
                identifierGenerator.generate(),
                result.messageId(),
                result.conversionId(),
                result.stage().name(),
                result.operationKey(),
                result.resultReference(),
                result.resultDigest(),
                result.providerWork(),
                terminalState,
                timestamp(identityClock.instant()));
    }

    private DeliveryDecision existingDelivery(UUID messageId) {
        List<DeliveryDecision> matches =
                jdbcTemplate.query(
                        """
                        SELECT disposition, stage_run_id, reason_code
                        FROM workflow.conversion_message_inbox WHERE message_id = ?
                        """,
                        (resultSet, row) ->
                                new DeliveryDecision(
                                        DeliveryDisposition.valueOf(
                                                resultSet.getString("disposition")),
                                        resultSet.getObject("stage_run_id", UUID.class),
                                        resultSet.getString("reason_code")),
                        messageId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private AcceptedResultReplay findAcceptedResult(UUID conversionId, String operationKey) {
        List<AcceptedResultReplay> matches =
                jdbcTemplate.query(
                        """
                        SELECT stage_run_id, accepted_result_id, stage, operation_key, result_reference,
                               result_sha256, provider_work, accepted_at
                        FROM workflow.conversion_accepted_result
                        WHERE conversion_id = ? AND operation_key = ?
                        """,
                        (resultSet, row) ->
                                new AcceptedResultReplay(
                                        resultSet.getObject("stage_run_id", UUID.class),
                                        acceptedResult(resultSet)),
                        conversionId,
                        operationKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static AcceptedResult acceptedResult(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new AcceptedResult(
                resultSet.getObject("accepted_result_id", UUID.class),
                Stage.valueOf(resultSet.getString("stage")),
                resultSet.getString("operation_key"),
                resultSet.getString("result_reference"),
                resultSet.getString("result_sha256"),
                resultSet.getBoolean("provider_work"),
                resultSet.getObject("accepted_at", OffsetDateTime.class).toInstant());
    }

    private DeliveryDecision recordDelivery(
            WorkDelivery delivery,
            DeliveryDisposition disposition,
            UUID stageRunId,
            String reasonCode) {
        int inserted =
                jdbcTemplate.update(
                        """
                        INSERT INTO workflow.conversion_message_inbox (
                            message_id, conversion_id, stage_run_id, stage, schema_version,
                            expected_conversion_version, disposition, reason_code, received_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (message_id) DO NOTHING
                        """,
                        delivery.messageId(),
                        delivery.conversionId(),
                        stageRunId,
                        delivery.stage().name(),
                        delivery.schemaVersion(),
                        delivery.expectedConversionVersion(),
                        disposition.name(),
                        reasonCode,
                        timestamp(identityClock.instant()));
        if (inserted == 0) {
            DeliveryDecision replay = existingDelivery(delivery.messageId());
            return new DeliveryDecision(
                    DeliveryDisposition.DUPLICATE, replay.stageRunId(), "MESSAGE_REPLAYED");
        }
        return new DeliveryDecision(disposition, stageRunId, reasonCode);
    }

    private static void validate(WorkDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(delivery.messageId(), "messageId");
        Objects.requireNonNull(delivery.conversionId(), "conversionId");
        Objects.requireNonNull(delivery.stage(), "stage");
        Objects.requireNonNull(delivery.leaseDuration(), "leaseDuration");
        if (delivery.workerId() == null
                || delivery.workerId().isBlank()
                || delivery.workerId().length() > 160) {
            throw new IllegalArgumentException(
                    "workerId is required and must be at most 160 characters");
        }
        if (delivery.leaseDuration().isZero()
                || delivery.leaseDuration().isNegative()
                || delivery.leaseDuration().compareTo(java.time.Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be between zero and one hour");
        }
    }

    private static void validate(StageResult result) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(result.messageId(), "messageId");
        Objects.requireNonNull(result.conversionId(), "conversionId");
        Objects.requireNonNull(result.stage(), "stage");
        requireOperationKey(result.operationKey());
        if (result.resultReference() == null
                || result.resultReference().isBlank()
                || result.resultReference().length() > 300) {
            throw new IllegalArgumentException(
                    "resultReference is required and must be at most 300 characters");
        }
        if (result.resultDigest() == null || !result.resultDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("resultDigest must be a lowercase SHA-256 digest");
        }
    }

    private static void validate(StageCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(checkpoint.messageId(), "messageId");
        Objects.requireNonNull(checkpoint.conversionId(), "conversionId");
        Objects.requireNonNull(checkpoint.stage(), "stage");
        if (checkpoint.checkpointReference() == null
                || checkpoint.checkpointReference().isBlank()
                || checkpoint.checkpointReference().length() > 300) {
            throw new IllegalArgumentException(
                    "checkpointReference is required and must be at most 300 characters");
        }
        if (checkpoint.checkpointDigest() == null
                || !checkpoint.checkpointDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "checkpointDigest must be a lowercase SHA-256 digest");
        }
    }

    private static void validate(StageFailure failure) {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(failure.messageId(), "messageId");
        Objects.requireNonNull(failure.conversionId(), "conversionId");
        Objects.requireNonNull(failure.stage(), "stage");
        if (failure.failureCode() == null
                || failure.failureCode().isBlank()
                || failure.failureCode().length() > 64) {
            throw new IllegalArgumentException(
                    "failureCode is required and must be at most 64 characters");
        }
    }

    private void validate(PauseCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.messageId(), "messageId");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.conversionId(), "conversionId");
        Objects.requireNonNull(command.safeResumeStage(), "safeResumeStage");
        Objects.requireNonNull(command.responsibleParty(), "responsibleParty");
        if (command.reasonCode() == null
                || command.reasonCode().isBlank()
                || command.reasonCode().length() > 64) {
            throw new IllegalArgumentException(
                    "pause reason is required and must be at most 64 characters");
        }
        if (command.deadline() != null && !command.deadline().isAfter(identityClock.instant())) {
            throw new IllegalArgumentException("pause deadline must be in the future");
        }
    }

    private static void validate(ResumeCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.conversionId(), "conversionId");
        requireOperationKey(command.idempotencyKey());
    }

    private static void validate(TerminalFailureCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.conversionId(), "conversionId");
        requireOperationKey(command.idempotencyKey());
        if (command.failureCode() == null
                || command.failureCode().isBlank()
                || command.failureCode().length() > 64) {
            throw new IllegalArgumentException(
                    "failureCode is required and must be at most 64 characters");
        }
    }

    private static void validate(ProviderCost command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.conversionId(), "conversionId");
        requireOperationKey(command.operationKey());
        if (command.incurredProviderCostMicros() <= 0) {
            throw new IllegalArgumentException("incurredProviderCostMicros must be positive");
        }
        if (command.evidenceReference() == null
                || command.evidenceReference().isBlank()
                || command.evidenceReference().length() > 300) {
            throw new IllegalArgumentException(
                    "evidenceReference is required and must be at most 300 characters");
        }
    }

    private static void requireOperationKey(String operationKey) {
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 200) {
            throw new IllegalArgumentException(
                    "operation key is required and must be at most 200 characters");
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static boolean requiresRecipe(Stage stage) {
        return switch (stage) {
            case SPEECH, ASSEMBLY, PACKAGING, FINALIZATION -> true;
            case INSPECTION, EXTRACTION, NARRATION_ANALYSIS -> false;
        };
    }

    private static String resumeConversionState(Stage stage) {
        return requiresRecipe(stage) ? "GENERATING" : "PREPARING";
    }

    private static String resumeReasonCode(Stage stage) {
        return switch (stage) {
            case INSPECTION -> "INSPECTION_PENDING";
            case EXTRACTION -> "EXTRACTION_PENDING";
            case NARRATION_ANALYSIS -> "NARRATION_PLAN_PENDING";
            case SPEECH, ASSEMBLY, PACKAGING -> "GENERATION_IN_PROGRESS";
            case FINALIZATION -> "FINAL_AUDIOBOOK_VALIDATION";
        };
    }

    private record ConversionPosition(String state, long version) {
        boolean terminal() {
            return switch (state) {
                case "FINALIZED", "FAILED", "CANCELLED", "CANCELLING" -> true;
                default -> false;
            };
        }
    }

    private record OwnedConversion(String state, long version) {
        boolean terminal() {
            return switch (state) {
                case "FINALIZED", "FAILED", "CANCELLED", "CANCELLING" -> true;
                default -> false;
            };
        }
    }

    private record StageLease(
            UUID stageRunId,
            UUID listenerId,
            StageState state,
            int attemptCount,
            int maximumAttempts,
            UUID leaseMessageId,
            Instant leaseExpiresAt) {}

    private record ConversionPause(
            long version,
            String reasonCode,
            ResponsibleParty responsibleParty,
            Stage safeResumeStage,
            Instant deadline) {}

    private record ResumeOperation(
            UUID listenerId, UUID conversionId, long expectedConversionVersion, Stage stage) {
        boolean matches(ResumeCommand command) {
            return listenerId.equals(command.listenerId())
                    && conversionId.equals(command.conversionId())
                    && expectedConversionVersion == command.expectedConversionVersion();
        }
    }

    private record CancellationOperation(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            long incurredProviderCostMicros,
            String requestReason) {
        boolean matchesListener(
                UUID listenerId, UUID conversionId, long expectedConversionVersion) {
            return this.listenerId.equals(listenerId)
                    && this.conversionId.equals(conversionId)
                    && this.expectedConversionVersion == expectedConversionVersion
                    && "listener-requested".equals(requestReason);
        }
    }

    private record CancellationCommand(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            long incurredProviderCostMicros,
            String requestReason,
            String idempotencyKey) {}

    private record TerminalFailureOperation(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String failureCode,
            long reusableCharacters,
            long incurredProviderCostMicros) {
        boolean matches(TerminalFailureCommand command) {
            return listenerId.equals(command.listenerId())
                    && conversionId.equals(command.conversionId())
                    && expectedConversionVersion == command.expectedConversionVersion()
                    && failureCode.equals(command.failureCode());
        }
    }

    private record AcceptedResultReplay(UUID stageRunId, AcceptedResult acceptedResult) {
        boolean matches(StageResult result) {
            return acceptedResult.stage() == result.stage()
                    && acceptedResult.resultReference().equals(result.resultReference())
                    && acceptedResult.providerWork() == result.providerWork();
        }
    }
}
