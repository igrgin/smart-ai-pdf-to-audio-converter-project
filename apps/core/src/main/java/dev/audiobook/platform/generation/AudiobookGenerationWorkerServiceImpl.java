package dev.audiobook.platform.generation;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.time.Duration;
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
    private final ConversionWorkflowService workflowService;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final String speechWorkerId = "speech-worker-" + UUID.randomUUID();
    private final String packagingWorkerId = "packaging-worker-" + UUID.randomUUID();

    @Override
    public int generatePending() {
        List<ConversionCoordinate> candidates = jdbcTemplate.query(
                """
                SELECT c.listener_id, c.conversion_id, c.version
                FROM workflow.audiobook_conversion c
                WHERE c.state = 'GENERATING'
                  AND (
                    NOT EXISTS (
                        SELECT 1 FROM generation.active_segment_manifest active
                        WHERE active.conversion_id = c.conversion_id
                    )
                    OR EXISTS (
                        SELECT 1
                        FROM generation.speech_segment s
                        JOIN generation.active_segment_manifest active
                          ON active.manifest_id = s.manifest_id
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
                        resultSet.getObject("conversion_id", UUID.class),
                        resultSet.getLong("version")));
        if (candidates.isEmpty()) {
            return 0;
        }
        ConversionCoordinate candidate = candidates.getFirst();
        workflowService.scheduleStage(
                candidate.listenerId(), candidate.conversionId(), ConversionWorkflowService.Stage.SPEECH, 4);
        UUID messageId = identifierGenerator.generate();
        ConversionWorkflowService.DeliveryDecision claim = workflowService.claimDelivery(
                new ConversionWorkflowService.WorkDelivery(
                        messageId,
                        candidate.conversionId(),
                        ConversionWorkflowService.Stage.SPEECH,
                        1,
                        candidate.version(),
                        speechWorkerId,
                        Duration.ofMinutes(30)));
        if (claim.disposition() != ConversionWorkflowService.DeliveryDisposition.CLAIMED) {
            return 0;
        }
        try {
            AudiobookGenerationService.GenerationManifest manifest = generationService.prepare(
                    candidate.listenerId(), candidate.conversionId());
            for (AudiobookGenerationService.Segment segment : manifest.segments()) {
                generationService.generateSegment(
                        candidate.listenerId(), candidate.conversionId(), segment.operationKey());
            }
            workflowService.acceptResult(new ConversionWorkflowService.StageResult(
                    messageId,
                    candidate.conversionId(),
                    ConversionWorkflowService.Stage.SPEECH,
                    "speech-stage:" + candidate.conversionId(),
                    "generation/manifests/" + manifest.manifestId(),
                    manifest.manifestDigest(),
                    true));
        } catch (RuntimeException exception) {
            workflowService.failStage(new ConversionWorkflowService.StageFailure(
                    messageId,
                    candidate.conversionId(),
                    ConversionWorkflowService.Stage.SPEECH,
                    "SPEECH_STAGE_FAILED",
                    true));
            throw exception;
        }
        return 1;
    }

    @Override
    public int packagePending() {
        List<ConversionCoordinate> candidates = jdbcTemplate.query(
                """
                SELECT m.listener_id, m.conversion_id, conversion.version
                FROM generation.active_segment_manifest active
                JOIN generation.segment_manifest m ON m.manifest_id = active.manifest_id
                JOIN workflow.audiobook_conversion conversion
                  ON conversion.conversion_id = m.conversion_id
                WHERE conversion.state = 'GENERATING'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM generation.speech_segment s
                    JOIN generation.active_segment_manifest selected
                      ON selected.manifest_id = s.manifest_id
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
                        resultSet.getObject("conversion_id", UUID.class),
                        resultSet.getLong("version")));
        if (candidates.isEmpty()) {
            return 0;
        }
        ConversionCoordinate candidate = candidates.getFirst();
        workflowService.scheduleStage(
                candidate.listenerId(), candidate.conversionId(), ConversionWorkflowService.Stage.PACKAGING, 3);
        UUID messageId = identifierGenerator.generate();
        ConversionWorkflowService.DeliveryDecision claim = workflowService.claimDelivery(
                new ConversionWorkflowService.WorkDelivery(
                        messageId,
                        candidate.conversionId(),
                        ConversionWorkflowService.Stage.PACKAGING,
                        1,
                        candidate.version(),
                        packagingWorkerId,
                        Duration.ofMinutes(30)));
        if (claim.disposition() != ConversionWorkflowService.DeliveryDisposition.CLAIMED) {
            return 0;
        }
        try {
            generationService.packageAudiobook(candidate.listenerId(), candidate.conversionId());
            String resultDigest = jdbcTemplate.queryForObject(
                    """
                    SELECT manifest_digest FROM generation.packaged_audiobook_result
                    WHERE conversion_id = ? AND listener_id = ?
                    """,
                    String.class,
                    candidate.conversionId(),
                    candidate.listenerId());
            workflowService.acceptResult(new ConversionWorkflowService.StageResult(
                    messageId,
                    candidate.conversionId(),
                    ConversionWorkflowService.Stage.PACKAGING,
                    "packaging-stage:" + candidate.conversionId(),
                    "generation/packaged-results/" + candidate.conversionId(),
                    resultDigest,
                    false));
        } catch (RuntimeException exception) {
            workflowService.failStage(new ConversionWorkflowService.StageFailure(
                    messageId,
                    candidate.conversionId(),
                    ConversionWorkflowService.Stage.PACKAGING,
                    "PACKAGING_STAGE_FAILED",
                    true));
            throw exception;
        }
        return 1;
    }

    private record ConversionCoordinate(UUID listenerId, UUID conversionId, long version) {
    }
}
