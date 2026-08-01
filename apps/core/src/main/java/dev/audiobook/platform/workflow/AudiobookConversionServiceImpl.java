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
    @Transactional
    public int applyNarrationPlanResults() {
        int ready = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion c
                SET state = 'AWAITING_REVIEW', reason_code = 'NARRATION_REVIEW_AVAILABLE', version = version + 1
                WHERE c.state = 'PREPARING' AND EXISTS (
                    SELECT 1
                    FROM workflow.narration_plan_work w
                    JOIN narration.narration_plan n ON n.conversion_id = w.conversion_id
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
        return ready + exhausted;
    }

    @Override
    public List<AudiobookConversion> conversions(UUID listenerId) {
        Objects.requireNonNull(listenerId, "listenerId");
        return jdbcTemplate.query(
                """
                SELECT conversion_id, state, reason_code, version FROM audiobook_conversion
                WHERE listener_id = ? ORDER BY created_at, conversion_id
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
                SELECT conversion_id, state, reason_code, version FROM audiobook_conversion
                WHERE listener_id = ? AND conversion_id = ?
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
        ConversionState state = ConversionState.valueOf(resultSet.getString("state"));
        return new AudiobookConversion(
                resultSet.getObject("conversion_id", UUID.class),
                state,
                resultSet.getString("reason_code"),
                state == ConversionState.AWAITING_REVIEW
                        ? List.of(AllowedAction.REVIEW_NARRATION_PLAN, AllowedAction.ACCEPT_RECOMMENDATIONS)
                        : List.of(),
                resultSet.getLong("version"));
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
