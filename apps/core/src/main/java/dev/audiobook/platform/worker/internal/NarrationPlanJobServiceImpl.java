package dev.audiobook.platform.worker.internal;

import dev.audiobook.platform.narration.NarrationPlanService;
import dev.audiobook.platform.narration.SourceTooDamagedException;

import dev.audiobook.platform.admission.QuarantineObjectStore;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class NarrationPlanJobServiceImpl implements NarrationPlanJobService {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);

    private final JdbcTemplate jdbcTemplate;
    private final QuarantineObjectStore objectStore;
    private final NarrationPlanService narrationPlanService;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final ConversionWorkflowService workflowService;
    private final ConversionLifecycleService lifecycleService;
    private final PlatformIdentifierGenerator identifierGenerator;

    @Override
    public int processPending() {
        renewExpiredDeliveries();
        List<DeliveryCoordinates> pending = jdbcTemplate.query(
                """
                SELECT o.message_id, w.work_id
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                WHERE (w.state = 'READY' OR (w.state = 'CLAIMED' AND w.lease_expires_at <= ?))
                  AND w.attempt_count < ?
                ORDER BY w.created_at, w.work_id
                LIMIT ?
                """,
                (resultSet, row) -> new DeliveryCoordinates(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("work_id", UUID.class)),
                Timestamp.from(clock.instant()),
                MAX_ATTEMPTS,
                BATCH_SIZE);
        int completed = 0;
        for (DeliveryCoordinates delivery : pending) {
            if (processDelivery(delivery.messageId(), delivery.workId())) {
                completed++;
            }
        }
        return completed;
    }

    @Override
    public boolean processDelivery(UUID messageId, UUID workId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(workId, "workId");
        PendingDelivery delivery = transactions().execute(status -> claim(messageId, workId));
        if (delivery == null) {
            return false;
        }
        try (var publication = objectStore.read(delivery.submissionId())) {
            narrationPlanService.prepare(delivery.listenerId(), delivery.conversionId(), publication);
            transactions().executeWithoutResult(status -> acceptResult(delivery));
            return true;
        } catch (SourceTooDamagedException exception) {
            pauseAfterDamage(workId, messageId, exception);
            return false;
        } catch (IOException exception) {
            releaseAfterFailure(workId, messageId);
            throw new IllegalStateException("Narration Plan source Working Asset is unavailable", exception);
        } catch (RuntimeException exception) {
            releaseAfterFailure(workId, messageId);
            throw exception;
        }
    }

    private PendingDelivery claim(UUID messageId, UUID workId) {
        Instant now = clock.instant();
        PendingDelivery delivery = jdbcTemplate.queryForObject(
                """
                SELECT w.listener_id, w.conversion_id, w.submission_id,
                       delivery.schema_version, delivery.expected_conversion_version
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_delivery delivery ON delivery.work_id = w.work_id
                WHERE w.work_id = ? AND delivery.message_id = ?
                """,
                (resultSet, row) -> new PendingDelivery(
                        messageId,
                        workId,
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getObject("conversion_id", UUID.class),
                        resultSet.getObject("submission_id", UUID.class),
                        resultSet.getInt("schema_version"),
                        resultSet.getLong("expected_conversion_version")),
                workId,
                messageId);
        ConversionWorkflowService.DeliveryDecision workflowClaim = workflowService.claimDelivery(
                new ConversionWorkflowService.WorkDelivery(
                        messageId,
                        delivery.conversionId(),
                        ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                        delivery.schemaVersion(),
                        delivery.expectedConversionVersion(),
                        "narration-worker",
                        LEASE_DURATION));
        if (workflowClaim.disposition() != ConversionWorkflowService.DeliveryDisposition.CLAIMED) {
            return null;
        }
        jdbcTemplate.update(
                """
                INSERT INTO workflow.narration_plan_inbox (message_id, work_id, accepted_at)
                VALUES (?, ?, ?) ON CONFLICT (message_id) DO NOTHING
                """,
                messageId,
                workId,
                Timestamp.from(now));
        int claimed = jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = 'CLAIMED', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_expires_at = ?
                WHERE work_id = ? AND attempt_count < ? AND (
                    state = 'READY' OR
                    (state = 'CLAIMED' AND lease_expires_at <= ?)
                )
                """,
                messageId,
                Timestamp.from(now.plus(LEASE_DURATION)),
                workId,
                MAX_ATTEMPTS,
                Timestamp.from(now));
        if (claimed == 0) {
            return null;
        }
        return delivery;
    }

    private void releaseAfterFailure(UUID workId, UUID messageId) {
        transactions().executeWithoutResult(status -> {
            PendingDelivery delivery = pendingDelivery(messageId, workId);
            workflowService.failStage(new ConversionWorkflowService.StageFailure(
                    messageId,
                    delivery.conversionId(),
                    ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                    "NARRATION_ANALYSIS_FAILED",
                    true));
            jdbcTemplate.update(
                    """
                    UPDATE workflow.narration_plan_work
                    SET state = CASE WHEN attempt_count >= ? THEN 'EXHAUSTED' ELSE 'READY' END,
                        lease_owner = NULL, lease_expires_at = NULL
                    WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                    """,
                    MAX_ATTEMPTS,
                    workId,
                    messageId);
            String state = jdbcTemplate.queryForObject(
                    "SELECT state FROM workflow.narration_plan_work WHERE work_id = ?",
                    String.class,
                    workId);
            if ("READY".equals(state)) {
                rotateDelivery(workId, messageId);
            }
        });
    }

    private void renewExpiredDeliveries() {
        List<DeliveryCoordinates> expired = jdbcTemplate.query(
                """
                SELECT o.message_id, w.work_id
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                WHERE w.state = 'CLAIMED' AND w.lease_expires_at <= ?
                  AND w.attempt_count < ?
                ORDER BY w.created_at, w.work_id
                LIMIT ?
                """,
                (resultSet, row) -> new DeliveryCoordinates(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("work_id", UUID.class)),
                Timestamp.from(clock.instant()),
                MAX_ATTEMPTS,
                BATCH_SIZE);
        for (DeliveryCoordinates delivery : expired) {
            rotateDelivery(delivery.workId(), delivery.messageId());
        }
    }

    private void rotateDelivery(UUID workId, UUID previousMessageId) {
        UUID nextMessageId = identifierGenerator.generate();
        Timestamp now = Timestamp.from(clock.instant());
        int recorded = jdbcTemplate.update(
                """
                INSERT INTO workflow.narration_plan_delivery (
                    message_id, work_id, schema_version, expected_conversion_version, created_at
                )
                SELECT ?, work_id, schema_version, expected_conversion_version, ?
                FROM workflow.narration_plan_delivery
                WHERE message_id = ? AND work_id = ?
                ON CONFLICT (message_id) DO NOTHING
                """,
                nextMessageId,
                now,
                previousMessageId,
                workId);
        if (recorded != 1) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_outbox
                SET message_id = ?, created_at = ?, published_at = NULL
                WHERE work_id = ? AND message_id = ?
                """,
                nextMessageId,
                now,
                workId,
                previousMessageId);
    }

    private void pauseAfterDamage(
            UUID workId, UUID messageId, SourceTooDamagedException exception) {
        transactions().executeWithoutResult(status -> {
            PendingDelivery delivery = pendingDelivery(messageId, workId);
            lifecycleService.pause(new ConversionLifecycleService.PauseCommand(
                    messageId,
                    delivery.listenerId(),
                    delivery.conversionId(),
                    ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                    exception.reasonCode(),
                    ConversionLifecycleService.ResponsibleParty.LISTENER,
                    null));
            jdbcTemplate.update(
                    """
                    UPDATE workflow.narration_plan_work
                    SET state = 'PAUSED', pause_reason_code = ?, resume_from_page = ?,
                        listener_guidance = ?, lease_owner = NULL, lease_expires_at = NULL
                    WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                    """,
                    exception.reasonCode(),
                    exception.resumeFromPage(),
                    exception.listenerGuidance(),
                    workId,
                    messageId);
        });
    }

    private void acceptResult(PendingDelivery delivery) {
        PlanResult plan = jdbcTemplate.queryForObject(
                """
                SELECT working_asset_ref, asset_sha256
                FROM narration.narration_plan
                WHERE listener_id = ? AND conversion_id = ?
                """,
                (resultSet, row) -> new PlanResult(
                        resultSet.getString("working_asset_ref"),
                        resultSet.getString("asset_sha256")),
                delivery.listenerId(),
                delivery.conversionId());
        ConversionWorkflowService.ResultDecision accepted = workflowService.acceptResult(
                new ConversionWorkflowService.StageResult(
                        delivery.messageId(),
                        delivery.conversionId(),
                        ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                        "narration-analysis:" + delivery.conversionId(),
                        plan.reference(),
                        plan.digest(),
                        false));
        if (accepted.disposition() != ConversionWorkflowService.ResultDisposition.ACCEPTED
                && accepted.disposition() != ConversionWorkflowService.ResultDisposition.REPLAYED) {
            throw new IllegalStateException("Narration analysis result was not accepted");
        }
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = 'SUCCEEDED', completed_at = ?, lease_owner = NULL, lease_expires_at = NULL
                WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                """,
                Timestamp.from(clock.instant()),
                delivery.workId(),
                delivery.messageId());
    }

    private PendingDelivery pendingDelivery(UUID messageId, UUID workId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT w.listener_id, w.conversion_id, w.submission_id,
                       delivery.schema_version, delivery.expected_conversion_version
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_delivery delivery ON delivery.work_id = w.work_id
                WHERE w.work_id = ? AND delivery.message_id = ?
                """,
                (resultSet, row) -> new PendingDelivery(
                        messageId,
                        workId,
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getObject("conversion_id", UUID.class),
                        resultSet.getObject("submission_id", UUID.class),
                        resultSet.getInt("schema_version"),
                        resultSet.getLong("expected_conversion_version")),
                workId,
                messageId);
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private record PendingDelivery(
            UUID messageId,
            UUID workId,
            UUID listenerId,
            UUID conversionId,
            UUID submissionId,
            int schemaVersion,
            long expectedConversionVersion) {
    }

    private record PlanResult(String reference, String digest) {
    }

    private record DeliveryCoordinates(UUID messageId, UUID workId) {
    }
}
