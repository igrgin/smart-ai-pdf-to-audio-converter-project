package dev.audiobook.platform.workflow;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AudiobookConversionFinalizationServiceImpl
        implements AudiobookConversionFinalizationService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void beginFinalizing(UUID listenerId, UUID conversionId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'FINALIZING', reason_code = 'FINAL_AUDIOBOOK_VALIDATION', version = version + 1
                WHERE listener_id = ? AND conversion_id = ? AND state = 'GENERATING'
                """,
                listenerId,
                conversionId);
        if (updated == 0 && !"FINALIZING".equals(state(listenerId, conversionId))) {
            throw new IllegalStateException("Audiobook Conversion cannot be finalized");
        }
    }

    @Override
    public void lockAndRequireFinalizing(UUID listenerId, UUID conversionId) {
        List<String> states = jdbcTemplate.query(
                """
                SELECT state FROM workflow.audiobook_conversion
                WHERE listener_id = ? AND conversion_id = ? FOR UPDATE
                """,
                (resultSet, row) -> resultSet.getString("state"),
                listenerId,
                conversionId);
        if (states.isEmpty() || !"FINALIZING".equals(states.getFirst())) {
            throw new IllegalStateException(
                    "Audiobook Conversion is not ready for visibility-last Finalization");
        }
    }

    @Override
    public void markFinalized(UUID listenerId, UUID conversionId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET state = 'FINALIZED', reason_code = 'PRIVATE_AUDIOBOOK_AVAILABLE', version = version + 1
                WHERE listener_id = ? AND conversion_id = ? AND state = 'FINALIZING'
                """,
                listenerId,
                conversionId);
        if (updated != 1) {
            throw new IllegalStateException("Audiobook Conversion Finalization was lost");
        }
    }

    private String state(UUID listenerId, UUID conversionId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT state FROM workflow.audiobook_conversion WHERE listener_id = ? AND conversion_id = ?",
                    String.class,
                    listenerId,
                    conversionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalStateException("Audiobook Conversion is unavailable", exception);
        }
    }
}
