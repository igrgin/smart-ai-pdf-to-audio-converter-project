package dev.audiobook.platform.workflow;

import dev.audiobook.platform.narration.NarrationSelectionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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

    @Override
    @Transactional
    public void createPreparing(UUID conversionId, UUID listenerId, UUID sourcePublicationId) {
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(sourcePublicationId, "sourcePublicationId");
        jdbcTemplate.update(
                """
                INSERT INTO audiobook_conversion (
                    conversion_id, listener_id, source_publication_id, state, reason_code, created_at
                ) VALUES (?, ?, ?, 'PREPARING', 'NARRATION_PLAN_PENDING', ?)
                """,
                conversionId,
                listenerId,
                sourcePublicationId,
                Timestamp.from(identityClock.instant()));
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

    @Override
    @Transactional
    public void markNarrationPlanReady(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        int updated = jdbcTemplate.update(
                """
                UPDATE audiobook_conversion
                SET state = 'AWAITING_REVIEW', reason_code = 'NARRATION_REVIEW_AVAILABLE', version = version + 1
                WHERE listener_id = ? AND conversion_id = ? AND state = 'PREPARING'
                """,
                listenerId,
                conversionId);
        if (updated == 0 && conversion(listenerId, conversionId).state() != ConversionState.AWAITING_REVIEW) {
            throw new IllegalStateException("Audiobook Conversion cannot accept a Narration Plan");
        }
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
