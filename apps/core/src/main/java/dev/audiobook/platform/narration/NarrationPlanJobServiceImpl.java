package dev.audiobook.platform.narration;

import dev.audiobook.platform.admission.QuarantineObjectStore;
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

    @Override
    public int processPending() {
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
            transactions().executeWithoutResult(status -> jdbcTemplate.update(
                    """
                    UPDATE workflow.narration_plan_work
                    SET state = 'SUCCEEDED', completed_at = ?, lease_owner = NULL, lease_expires_at = NULL
                    WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                    """,
                    Timestamp.from(clock.instant()),
                    workId,
                    messageId));
            return true;
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
        return jdbcTemplate.queryForObject(
                """
                SELECT listener_id, conversion_id, submission_id
                FROM workflow.narration_plan_work WHERE work_id = ?
                """,
                (resultSet, row) -> new PendingDelivery(
                        messageId,
                        workId,
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getObject("conversion_id", UUID.class),
                        resultSet.getObject("submission_id", UUID.class)),
                workId);
    }

    private void releaseAfterFailure(UUID workId, UUID messageId) {
        transactions().executeWithoutResult(status -> jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = CASE WHEN attempt_count >= ? THEN 'EXHAUSTED' ELSE 'READY' END,
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                """,
                MAX_ATTEMPTS,
                workId,
                messageId));
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private record PendingDelivery(
            UUID messageId, UUID workId, UUID listenerId, UUID conversionId, UUID submissionId) {
    }

    private record DeliveryCoordinates(UUID messageId, UUID workId) {
    }
}
