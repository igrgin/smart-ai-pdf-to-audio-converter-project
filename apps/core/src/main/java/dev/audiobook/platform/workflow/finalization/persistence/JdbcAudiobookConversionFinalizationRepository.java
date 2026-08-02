package dev.audiobook.platform.workflow.finalization.persistence;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JdbcAudiobookConversionFinalizationRepository {

    private final JdbcTemplate jdbcTemplate;

    public int beginFinalizing(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'FINALIZING', reason_code = 'FINAL_AUDIOBOOK_VALIDATION', version = version + 1
                WHERE listener_id = ? AND conversion_id = ? AND state = 'GENERATING'
                """,
                listenerId,
                conversionId);
    }

    public List<String> lockState(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.query(
                """
                SELECT state FROM workflow.audiobook_conversion
                WHERE listener_id = ? AND conversion_id = ? FOR UPDATE
                """,
                (resultSet, row) -> resultSet.getString("state"),
                listenerId,
                conversionId);
    }

    public int markFinalized(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'FINALIZED', reason_code = 'PRIVATE_AUDIOBOOK_AVAILABLE', version = version + 1
                WHERE listener_id = ? AND conversion_id = ? AND state = 'FINALIZING'
                """,
                listenerId,
                conversionId);
    }

    public Optional<String> state(UUID listenerId, UUID conversionId) {
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(
                            "SELECT state FROM workflow.audiobook_conversion WHERE listener_id = ?"
                                    + " AND conversion_id = ?",
                            String.class,
                            listenerId,
                            conversionId));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }
}
