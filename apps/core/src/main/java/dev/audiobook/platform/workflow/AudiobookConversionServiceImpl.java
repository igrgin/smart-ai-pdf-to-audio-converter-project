package dev.audiobook.platform.workflow;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationSelectionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AudiobookConversionServiceImpl implements AudiobookConversionService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;
    private final NarrationSelectionService narrationSelectionService;
    private final PlatformIdentifierGenerator identifierGenerator;

    @Override
    @Transactional
    public void createPreparing(
            UUID conversionId,
            UUID listenerId,
            UUID sourcePublicationId,
            PreparationReason preparationReason) {
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(sourcePublicationId, "sourcePublicationId");
        Objects.requireNonNull(preparationReason, "preparationReason");
        jdbcTemplate.update(
                """
                INSERT INTO audiobook_conversion (
                    conversion_id, listener_id, source_publication_id, state, reason_code, created_at
                ) VALUES (?, ?, ?, 'PREPARING', ?, ?)
                """,
                conversionId,
                listenerId,
                sourcePublicationId,
                preparationReason.name(),
                Timestamp.from(identityClock.instant()));
    }

    @Override
    @Transactional
    public void scheduleNarrationPlan(UUID listenerId, UUID conversionId, UUID submissionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(submissionId, "submissionId");
        UUID workId = identifierGenerator.generate();
        Timestamp now = Timestamp.from(identityClock.instant());
        jdbcTemplate.update(
                """
                INSERT INTO workflow.narration_plan_work (
                    work_id, listener_id, conversion_id, submission_id, operation_key, state, created_at
                ) VALUES (?, ?, ?, ?, ?, 'READY', ?)
                """,
                workId,
                listenerId,
                conversionId,
                submissionId,
                "narration-plan:" + conversionId,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO workflow.narration_plan_outbox (
                    message_id, work_id, message_type, schema_version, created_at
                ) VALUES (?, ?, 'PREPARE_NARRATION_PLAN', 1, ?)
                """,
                identifierGenerator.generate(),
                workId,
                now);
    }

    @Override
    public int relayNarrationPlanWork(BiConsumer<UUID, UUID> publisher) {
        Objects.requireNonNull(publisher, "publisher");
        List<PendingMessage> pending = jdbcTemplate.query(
                """
                SELECT message_id, work_id
                FROM workflow.narration_plan_outbox
                WHERE published_at IS NULL
                ORDER BY created_at, message_id
                LIMIT 20
                """,
                (resultSet, row) -> new PendingMessage(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("work_id", UUID.class)));
        int published = 0;
        for (PendingMessage message : pending) {
            publisher.accept(message.messageId(), message.workId());
            published += jdbcTemplate.update(
                    """
                    UPDATE workflow.narration_plan_outbox SET published_at = ?
                    WHERE message_id = ? AND published_at IS NULL
                    """,
                    Timestamp.from(identityClock.instant()),
                    message.messageId());
        }
        return published;
    }

    @Override
    public List<UUID> narrationPlanRecoveryCandidates() {
        return jdbcTemplate.query(
                """
                SELECT w.conversion_id
                FROM workflow.narration_plan_work w
                JOIN workflow.audiobook_conversion c ON c.conversion_id = w.conversion_id
                WHERE w.state IN ('READY', 'CLAIMED') AND c.state = 'PREPARING'
                ORDER BY w.created_at, w.work_id
                LIMIT 100
                """,
                (resultSet, row) -> resultSet.getObject("conversion_id", UUID.class));
    }

    @Override
    @Transactional
    public int applyNarrationPlanResults(List<UUID> planPresentConversionIds) {
        Objects.requireNonNull(planPresentConversionIds, "planPresentConversionIds");
        Timestamp now = Timestamp.from(identityClock.instant());
        for (UUID conversionId : List.copyOf(planPresentConversionIds)) {
            Objects.requireNonNull(conversionId, "conversionId");
            jdbcTemplate.update(
                    """
                    UPDATE workflow.narration_plan_work
                    SET state = 'SUCCEEDED', completed_at = ?, lease_owner = NULL, lease_expires_at = NULL
                    WHERE conversion_id = ? AND state IN ('READY', 'CLAIMED')
                    """,
                    now,
                    conversionId);
        }
        int ready = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion c
                SET state = 'AWAITING_REVIEW', reason_code = 'NARRATION_REVIEW_AVAILABLE', version = version + 1
                WHERE c.state = 'PREPARING' AND EXISTS (
                    SELECT 1
                    FROM workflow.narration_plan_work w
                    WHERE w.conversion_id = c.conversion_id AND w.state = 'SUCCEEDED'
                )
                """);
        int exhausted = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion c
                SET reason_code = 'NARRATION_PLAN_REQUIRES_INTERVENTION', version = version + 1
                WHERE c.state = 'PREPARING'
                  AND c.reason_code <> 'NARRATION_PLAN_REQUIRES_INTERVENTION'
                  AND EXISTS (
                    SELECT 1 FROM workflow.narration_plan_work w
                    WHERE w.conversion_id = c.conversion_id AND w.state = 'EXHAUSTED'
                )
                """);
        int paused = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion c
                SET state = 'PAUSED', reason_code = 'SOURCE_TOO_DAMAGED', version = version + 1
                WHERE c.state = 'PREPARING'
                  AND c.reason_code <> 'SOURCE_TOO_DAMAGED'
                  AND EXISTS (
                    SELECT 1 FROM workflow.narration_plan_work w
                    WHERE w.conversion_id = c.conversion_id
                      AND w.state = 'PAUSED'
                      AND w.pause_reason_code = 'SOURCE_TOO_DAMAGED'
                )
                """);
        return ready + exhausted + paused;
    }

    @Override
    public List<AudiobookConversion> conversions(UUID listenerId) {
        Objects.requireNonNull(listenerId, "listenerId");
        return jdbcTemplate.query(
                """
                SELECT c.conversion_id, c.state, c.reason_code, c.version,
                       w.resume_from_page, w.listener_guidance
                FROM audiobook_conversion c
                LEFT JOIN narration_plan_work w ON w.conversion_id = c.conversion_id
                WHERE c.listener_id = ? ORDER BY c.created_at, c.conversion_id
                """,
                (resultSet, row) -> conversion(resultSet),
                listenerId);
    }

    @Override
    public AudiobookConversion conversion(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        List<AudiobookConversion> matches = jdbcTemplate.query(
                """
                SELECT c.conversion_id, c.state, c.reason_code, c.version,
                       w.resume_from_page, w.listener_guidance
                FROM audiobook_conversion c
                LEFT JOIN narration_plan_work w ON w.conversion_id = c.conversion_id
                WHERE c.listener_id = ? AND c.conversion_id = ?
                """,
                (resultSet, row) -> conversion(resultSet),
                listenerId,
                conversionId);
        if (matches.isEmpty()) {
            throw new AudiobookConversionUnavailableException();
        }
        return matches.getFirst();
    }

    private static AudiobookConversion conversion(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String reasonCode = resultSet.getString("reason_code");
        ConversionState state = ConversionState.valueOf(resultSet.getString("state"));
        Integer resumeFromPage = resultSet.getObject("resume_from_page", Integer.class);
        return new AudiobookConversion(
                resultSet.getObject("conversion_id", UUID.class),
                state,
                reasonCode,
                state == ConversionState.AWAITING_REVIEW
                                && "NARRATION_REVIEW_AVAILABLE".equals(resultSet.getString("reason_code"))
                        ? List.of(AllowedAction.REVIEW_NARRATION_PLAN, AllowedAction.ACCEPT_RECOMMENDATIONS)
                        : state == ConversionState.PAUSED ? List.of(AllowedAction.RETRY_NARRATION_PLAN) : List.of(),
                resultSet.getLong("version"),
                resumeFromPage == null
                        ? null
                        : new RecoveryDetails(resumeFromPage, resultSet.getString("listener_guidance")));
    }

    @Override
    @Transactional
    public AudiobookConversion resumeNarrationPlan(
            UUID listenerId, UUID conversionId, long expectedVersion, String idempotencyKey) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 200 characters");
        }
        conversion(listenerId, conversionId);
        Timestamp now = Timestamp.from(identityClock.instant());
        int accepted = jdbcTemplate.update(
                """
                INSERT INTO workflow.narration_plan_resume_operation (
                    operation_key, listener_id, conversion_id, expected_version, created_at
                ) VALUES (?, ?, ?, ?, ?) ON CONFLICT (operation_key) DO NOTHING
                """,
                idempotencyKey,
                listenerId,
                conversionId,
                expectedVersion,
                now);
        if (accepted == 0) {
            Integer replay = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM workflow.narration_plan_resume_operation
                    WHERE operation_key = ? AND listener_id = ? AND conversion_id = ?
                      AND expected_version = ?
                    """,
                    Integer.class,
                    idempotencyKey,
                    listenerId,
                    conversionId,
                    expectedVersion);
            if (replay != null && replay > 0) {
                return conversion(listenerId, conversionId);
            }
            throw new IllegalArgumentException("Idempotency-Key was already used for another recovery action");
        }
        int resumed = jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work w
                SET state = 'READY', attempt_count = 0, pause_reason_code = NULL,
                    resume_from_page = NULL, listener_guidance = NULL,
                    lease_owner = NULL, lease_expires_at = NULL
                FROM workflow.audiobook_conversion c
                WHERE w.conversion_id = c.conversion_id
                  AND w.conversion_id = ? AND w.listener_id = ?
                  AND w.state = 'PAUSED' AND c.state = 'PAUSED'
                  AND c.reason_code = 'SOURCE_TOO_DAMAGED'
                  AND c.version = ?
                """,
                conversionId,
                listenerId,
                expectedVersion);
        if (resumed == 0) {
            throw new IllegalStateException("Damaged-source recovery is unavailable or stale");
        }
        jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'PREPARING', reason_code = 'NARRATION_PLAN_PENDING', version = version + 1
                WHERE conversion_id = ? AND listener_id = ? AND state = 'PAUSED' AND version = ?
                """,
                conversionId,
                listenerId,
                expectedVersion);
        UUID workId = jdbcTemplate.queryForObject(
                "SELECT work_id FROM workflow.narration_plan_work WHERE conversion_id = ? AND listener_id = ?",
                UUID.class,
                conversionId,
                listenerId);
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_outbox
                SET message_id = ?, created_at = ?, published_at = NULL
                WHERE work_id = ?
                """,
                identifierGenerator.generate(),
                now,
                workId);
        return conversion(listenerId, conversionId);
    }

    private record PendingMessage(UUID messageId, UUID workId) {
    }

    @Override
    @Transactional
    public NarrationSelectionService.GenerationAuthorization beginSpeechGeneration(
            UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        NarrationSelectionService.GenerationAuthorization authorization =
                narrationSelectionService.authorizeGeneration(listenerId, conversionId);
        int started = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'GENERATING', reason_code = 'GENERATION_IN_PROGRESS', version = version + 1
                WHERE conversion_id = ? AND listener_id = ? AND state = 'AWAITING_REVIEW'
                """,
                conversionId,
                listenerId);
        if (started == 0) {
            String state = jdbcTemplate.queryForObject(
                    """
                    SELECT state FROM workflow.audiobook_conversion
                    WHERE conversion_id = ? AND listener_id = ?
                    """,
                    String.class,
                    conversionId,
                    listenerId);
            if (!ConversionState.GENERATING.name().equals(state)) {
                throw new IllegalStateException("Audiobook Conversion cannot begin speech generation");
            }
        }
        return authorization;
    }
}
