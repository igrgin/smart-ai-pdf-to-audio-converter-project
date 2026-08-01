package dev.audiobook.platform.workflow;

import dev.audiobook.platform.generation.AudiobookGenerationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile({"dev", "prod"})
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class AudiobookGenerationResultApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final AudiobookGenerationService generationService;

    @Scheduled(fixedDelayString = "${platform.generation.result-application-delay:1s}")
    public void apply() {
        List<ConversionCoordinate> candidates = jdbcTemplate.query(
                """
                SELECT c.listener_id, c.conversion_id
                FROM workflow.audiobook_conversion c
                JOIN generation.packaged_audiobook_result p ON p.conversion_id = c.conversion_id
                WHERE c.state IN ('GENERATING', 'FINALIZING')
                  AND NOT EXISTS (
                    SELECT 1 FROM library.private_audiobook a
                    WHERE a.conversion_id = c.conversion_id
                  )
                ORDER BY p.created_at, p.conversion_id
                LIMIT 1
                """,
                (resultSet, row) -> new ConversionCoordinate(
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getObject("conversion_id", UUID.class)));
        if (!candidates.isEmpty()) {
            ConversionCoordinate candidate = candidates.getFirst();
            generationService.finalizeAudiobook(candidate.listenerId(), candidate.conversionId());
        }
    }

    record ConversionCoordinate(UUID listenerId, UUID conversionId) {
    }
}
