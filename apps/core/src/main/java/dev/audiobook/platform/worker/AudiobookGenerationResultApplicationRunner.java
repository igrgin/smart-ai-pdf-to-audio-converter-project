package dev.audiobook.platform.worker;

import dev.audiobook.platform.generation.service.AudiobookGenerationService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Profile({"dev", "prod"})
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class AudiobookGenerationResultApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final AudiobookGenerationService generationService;

    @Scheduled(fixedDelayString = "${platform.generation.result-application-delay:1s}")
    public void apply() {
        List<ConversionCoordinate> candidates =
                jdbcTemplate.query(
                        """
                        SELECT c.listener_id, c.conversion_id
                        FROM workflow.audiobook_conversion c
                        JOIN generation.packaged_audiobook_result p ON p.conversion_id = c.conversion_id
                        JOIN workflow.conversion_stage_run stage
                          ON stage.conversion_id = c.conversion_id AND stage.stage = 'PACKAGING'
                        JOIN workflow.conversion_accepted_result accepted
                          ON accepted.stage_run_id = stage.stage_run_id
                         AND accepted.operation_key = 'packaging-stage:' || c.conversion_id
                         AND accepted.result_sha256 = p.manifest_digest
                        WHERE c.state IN ('GENERATING', 'FINALIZING')
                          AND stage.state = 'SUCCEEDED'
                          AND NOT EXISTS (
                            SELECT 1 FROM library.private_audiobook a
                            WHERE a.conversion_id = c.conversion_id
                          )
                        ORDER BY p.created_at, p.conversion_id
                        LIMIT 1
                        """,
                        (resultSet, row) ->
                                new ConversionCoordinate(
                                        resultSet.getObject("listener_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class)));
        if (!candidates.isEmpty()) {
            ConversionCoordinate candidate = candidates.getFirst();
            generationService.finalizeAudiobook(candidate.listenerId(), candidate.conversionId());
        }
    }

    record ConversionCoordinate(UUID listenerId, UUID conversionId) {}
}
