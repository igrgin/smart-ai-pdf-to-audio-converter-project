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
                    conversion_id, listener_id, source_publication_id, state, created_at
                ) VALUES (?, ?, ?, 'PREPARING', ?)
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
                SELECT conversion_id, state FROM audiobook_conversion
                WHERE listener_id = ? ORDER BY created_at, conversion_id
                """,
                (resultSet, row) -> new AudiobookConversion(
                        resultSet.getObject("conversion_id", UUID.class),
                        ConversionState.valueOf(resultSet.getString("state"))),
                listenerId);
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
                UPDATE workflow.audiobook_conversion SET state = 'GENERATING'
                WHERE conversion_id = ? AND listener_id = ? AND state = 'PREPARING'
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
