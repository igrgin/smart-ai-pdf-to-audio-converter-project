package dev.audiobook.platform.generation;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AudiobookGenerationWorkerServiceImpl implements AudiobookGenerationWorkerService {

    private final JdbcTemplate jdbcTemplate;
    private final AudiobookGenerationService generationService;

    @Override
    public int generatePending() {
        List<ConversionCoordinate> candidates = jdbcTemplate.query(
                """
                SELECT c.listener_id, c.conversion_id
                FROM workflow.audiobook_conversion c
                WHERE c.state = 'GENERATING'
                  AND (
                    NOT EXISTS (
                        SELECT 1 FROM generation.segment_manifest m
                        WHERE m.conversion_id = c.conversion_id
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM generation.speech_segment s
                        LEFT JOIN generation.accepted_segment a
                          ON a.operation_key = s.operation_key
                        WHERE s.conversion_id = c.conversion_id
                          AND a.operation_key IS NULL
                    )
                  )
                ORDER BY c.created_at, c.conversion_id
                LIMIT 1
                """,
                (resultSet, row) -> new ConversionCoordinate(
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getObject("conversion_id", UUID.class)));
        if (candidates.isEmpty()) {
            return 0;
        }
        ConversionCoordinate candidate = candidates.getFirst();
        AudiobookGenerationService.GenerationManifest manifest = generationService.prepare(
                candidate.listenerId(), candidate.conversionId());
        for (AudiobookGenerationService.Segment segment : manifest.segments()) {
            generationService.generateSegment(
                    candidate.listenerId(), candidate.conversionId(), segment.operationKey());
        }
        return 1;
    }

    @Override
    public int packagePending() {
        List<ConversionCoordinate> candidates = jdbcTemplate.query(
                """
                SELECT m.listener_id, m.conversion_id
                FROM generation.segment_manifest m
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM generation.speech_segment s
                    LEFT JOIN generation.accepted_segment a
                      ON a.operation_key = s.operation_key
                    WHERE s.conversion_id = m.conversion_id
                      AND a.operation_key IS NULL
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM generation.packaged_audiobook_result p
                    WHERE p.conversion_id = m.conversion_id
                  )
                ORDER BY m.created_at, m.conversion_id
                LIMIT 1
                """,
                (resultSet, row) -> new ConversionCoordinate(
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getObject("conversion_id", UUID.class)));
        if (candidates.isEmpty()) {
            return 0;
        }
        ConversionCoordinate candidate = candidates.getFirst();
        generationService.packageAudiobook(candidate.listenerId(), candidate.conversionId());
        return 1;
    }

    private record ConversionCoordinate(UUID listenerId, UUID conversionId) {
    }
}
