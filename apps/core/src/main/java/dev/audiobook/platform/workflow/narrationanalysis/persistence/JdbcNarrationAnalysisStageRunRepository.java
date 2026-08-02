package dev.audiobook.platform.workflow.narrationanalysis.persistence;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcNarrationAnalysisStageRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<DeliveryCoordinates> pending(Instant now, int maximumAttempts, int batchSize) {
        return jdbcTemplate.query(
                """
                SELECT o.message_id, w.work_id
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                WHERE (w.state = 'READY' OR (w.state = 'CLAIMED' AND w.lease_expires_at <= ?))
                  AND w.attempt_count < ?
                ORDER BY w.created_at, w.work_id
                LIMIT ?
                """,
                (resultSet, row) ->
                        new DeliveryCoordinates(
                                resultSet.getObject("message_id", UUID.class),
                                resultSet.getObject("work_id", UUID.class)),
                Timestamp.from(now),
                maximumAttempts,
                batchSize);
    }

    public List<DeliveryCoordinates> expired(Instant now, int maximumAttempts, int batchSize) {
        return jdbcTemplate.query(
                """
                SELECT o.message_id, w.work_id
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                WHERE w.state = 'CLAIMED' AND w.lease_expires_at <= ?
                  AND w.attempt_count < ?
                ORDER BY w.created_at, w.work_id
                LIMIT ?
                """,
                (resultSet, row) ->
                        new DeliveryCoordinates(
                                resultSet.getObject("message_id", UUID.class),
                                resultSet.getObject("work_id", UUID.class)),
                Timestamp.from(now),
                maximumAttempts,
                batchSize);
    }

    public PendingDelivery delivery(UUID messageId, UUID workId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT w.listener_id, w.conversion_id, w.submission_id,
                       delivery.schema_version, delivery.expected_conversion_version
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_delivery delivery ON delivery.work_id = w.work_id
                WHERE w.work_id = ? AND delivery.message_id = ?
                """,
                (resultSet, row) ->
                        new PendingDelivery(
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

    public boolean claim(
            PendingDelivery delivery, Instant now, Instant leaseExpiresAt, int maximumAttempts) {
        jdbcTemplate.update(
                """
                INSERT INTO workflow.narration_plan_inbox (message_id, work_id, accepted_at)
                VALUES (?, ?, ?) ON CONFLICT (message_id) DO NOTHING
                """,
                delivery.messageId(),
                delivery.workId(),
                Timestamp.from(now));
        return jdbcTemplate.update(
                        """
                        UPDATE workflow.narration_plan_work
                        SET state = 'CLAIMED', attempt_count = attempt_count + 1,
                            lease_owner = ?, lease_expires_at = ?
                        WHERE work_id = ? AND attempt_count < ? AND (
                            state = 'READY' OR
                            (state = 'CLAIMED' AND lease_expires_at <= ?)
                        )
                        """,
                        delivery.messageId(),
                        Timestamp.from(leaseExpiresAt),
                        delivery.workId(),
                        maximumAttempts,
                        Timestamp.from(now))
                == 1;
    }

    public boolean release(UUID workId, UUID messageId, int maximumAttempts) {
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = CASE WHEN attempt_count >= ? THEN 'EXHAUSTED' ELSE 'READY' END,
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                """,
                maximumAttempts,
                workId,
                messageId);
        return "READY"
                .equals(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM workflow.narration_plan_work WHERE work_id = ?",
                                String.class,
                                workId));
    }

    public void rotate(UUID workId, UUID previousMessageId, UUID nextMessageId, Instant now) {
        int recorded =
                jdbcTemplate.update(
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
                        Timestamp.from(now),
                        previousMessageId,
                        workId);
        if (recorded == 1) {
            jdbcTemplate.update(
                    """
                    UPDATE workflow.narration_plan_outbox
                    SET message_id = ?, created_at = ?, published_at = NULL
                    WHERE work_id = ? AND message_id = ?
                    """,
                    nextMessageId,
                    Timestamp.from(now),
                    workId,
                    previousMessageId);
        }
    }

    public void pause(
            UUID workId,
            UUID messageId,
            String reasonCode,
            Integer resumeFromPage,
            String listenerGuidance) {
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = 'PAUSED', pause_reason_code = ?, resume_from_page = ?,
                    listener_guidance = ?, lease_owner = NULL, lease_expires_at = NULL
                WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                """,
                reasonCode,
                resumeFromPage,
                listenerGuidance,
                workId,
                messageId);
    }

    public void succeed(UUID workId, UUID messageId, Instant completedAt) {
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = 'SUCCEEDED', completed_at = ?, lease_owner = NULL, lease_expires_at = NULL
                WHERE work_id = ? AND state = 'CLAIMED' AND lease_owner = ?
                """,
                Timestamp.from(completedAt),
                workId,
                messageId);
    }

    public record PendingDelivery(
            UUID messageId,
            UUID workId,
            UUID listenerId,
            UUID conversionId,
            UUID submissionId,
            int schemaVersion,
            long expectedConversionVersion) {}

    public record DeliveryCoordinates(UUID messageId, UUID workId) {}
}
