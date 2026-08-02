package dev.audiobook.platform.generation.service;

import dev.audiobook.platform.generation.*;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
        List<ConversionCoordinate> candidates =
                jdbcTemplate.query(
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
                            OR NOT EXISTS (
                                SELECT 1 FROM workflow.conversion_stage_run stage
                                WHERE stage.conversion_id = c.conversion_id
                                  AND stage.stage = 'SPEECH' AND stage.state = 'SUCCEEDED'
                            )
                          )
                        ORDER BY c.created_at, c.conversion_id
                        LIMIT 1
                        """,
                        (resultSet, row) ->
                                new ConversionCoordinate(
                                        resultSet.getObject("listener_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getLong("version")));
        if (candidates.isEmpty()) {
            return 0;
        }
        ConversionCoordinate candidate = candidates.getFirst();
        workflowService.scheduleStage(
                candidate.listenerId(),
                candidate.conversionId(),
                ConversionWorkflowService.Stage.SPEECH,
                4);
        UUID messageId = identifierGenerator.generate();
        ConversionWorkflowService.DeliveryDecision claim =
                workflowService.claimDelivery(
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
            AudiobookGenerationService.GenerationManifest manifest =
                    generationService.prepare(candidate.listenerId(), candidate.conversionId());
            for (AudiobookGenerationService.Segment segment : manifest.segments()) {
                if (!workflowService.claimActive(
                        messageId,
                        candidate.conversionId(),
                        ConversionWorkflowService.Stage.SPEECH)) {
                    return 0;
                }
                generationService.generateSegment(
                        new AudiobookGenerationService.ProviderCallCommand(
                                candidate.listenerId(),
                                candidate.conversionId(),
                                segment.operationKey(),
                                messageId));
            }
            if (!workflowService.claimActive(
                    messageId, candidate.conversionId(), ConversionWorkflowService.Stage.SPEECH)) {
                return 0;
            }
            ConversionWorkflowService.ResultDecision accepted =
                    workflowService.acceptResult(
                            new ConversionWorkflowService.StageResult(
                                    messageId,
                                    candidate.conversionId(),
                                    ConversionWorkflowService.Stage.SPEECH,
                                    "speech-stage:" + candidate.conversionId(),
                                    "generation/manifests/" + manifest.manifestId(),
                                    manifest.manifestDigest(),
                                    true));
            if (accepted.disposition() != ConversionWorkflowService.ResultDisposition.ACCEPTED
                    && accepted.disposition()
                            != ConversionWorkflowService.ResultDisposition.REPLAYED) {
                return 0;
            }
        } catch (RuntimeException exception) {
            workflowService.failStage(
                    new ConversionWorkflowService.StageFailure(
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
        List<ConversionCoordinate> candidates =
                jdbcTemplate.query(
                        """
                        SELECT m.listener_id, m.conversion_id, conversion.version,
                               CASE WHEN EXISTS (
                                   SELECT 1 FROM workflow.conversion_stage_run assembly
                                   WHERE assembly.conversion_id = m.conversion_id
                                     AND assembly.stage = 'ASSEMBLY' AND assembly.state = 'SUCCEEDED'
                               ) THEN 'PACKAGING' ELSE 'ASSEMBLY' END AS pending_stage
                        FROM generation.active_segment_manifest active
                        JOIN generation.segment_manifest m ON m.manifest_id = active.manifest_id
                        JOIN workflow.audiobook_conversion conversion
                          ON conversion.conversion_id = m.conversion_id
                        WHERE conversion.state = 'GENERATING'
                          AND EXISTS (
                            SELECT 1
                            FROM workflow.conversion_stage_run speech
                            WHERE speech.conversion_id = m.conversion_id
                              AND speech.stage = 'SPEECH' AND speech.state = 'SUCCEEDED'
                              AND EXISTS (
                                SELECT 1 FROM workflow.conversion_accepted_result accepted
                                WHERE accepted.conversion_id = speech.conversion_id
                                  AND accepted.stage = 'SPEECH'
                              )
                          )
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
                          AND (
                            NOT EXISTS (
                              SELECT 1 FROM workflow.conversion_stage_run stage
                              WHERE stage.conversion_id = m.conversion_id
                                AND stage.stage = 'ASSEMBLY' AND stage.state = 'SUCCEEDED'
                            ) OR NOT EXISTS (
                            SELECT 1 FROM workflow.conversion_stage_run stage
                            WHERE stage.conversion_id = m.conversion_id
                              AND stage.stage = 'PACKAGING' AND stage.state = 'SUCCEEDED'
                            )
                          )
                        ORDER BY m.created_at, m.conversion_id
                        LIMIT 1
                        """,
                        (resultSet, row) ->
                                new ConversionCoordinate(
                                        resultSet.getObject("listener_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getLong("version"),
                                        ConversionWorkflowService.Stage.valueOf(
                                                resultSet.getString("pending_stage"))));
        if (candidates.isEmpty()) {
            return 0;
        }
        ConversionCoordinate candidate = candidates.getFirst();
        workflowService.scheduleStage(
                candidate.listenerId(), candidate.conversionId(), candidate.stage(), 3);
        UUID messageId = identifierGenerator.generate();
        ConversionWorkflowService.DeliveryDecision claim =
                workflowService.claimDelivery(
                        new ConversionWorkflowService.WorkDelivery(
                                messageId,
                                candidate.conversionId(),
                                candidate.stage(),
                                1,
                                candidate.version(),
                                packagingWorkerId,
                                Duration.ofMinutes(30)));
        if (claim.disposition() != ConversionWorkflowService.DeliveryDisposition.CLAIMED) {
            return 0;
        }
        try {
            generationService.packageAudiobook(candidate.listenerId(), candidate.conversionId());
            String resultDigest =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT manifest_digest FROM generation.packaged_audiobook_result
                            WHERE conversion_id = ? AND listener_id = ?
                            """,
                            String.class,
                            candidate.conversionId(),
                            candidate.listenerId());
            ConversionWorkflowService.ResultDecision accepted =
                    workflowService.acceptResult(
                            new ConversionWorkflowService.StageResult(
                                    messageId,
                                    candidate.conversionId(),
                                    candidate.stage(),
                                    candidate.stage().name().toLowerCase()
                                            + "-stage:"
                                            + candidate.conversionId(),
                                    "generation/"
                                            + candidate.stage().name().toLowerCase()
                                            + "-results/"
                                            + candidate.conversionId(),
                                    resultDigest,
                                    false));
            if (accepted.disposition() != ConversionWorkflowService.ResultDisposition.ACCEPTED
                    && accepted.disposition()
                            != ConversionWorkflowService.ResultDisposition.REPLAYED) {
                return 0;
            }
        } catch (RuntimeException exception) {
            workflowService.failStage(
                    new ConversionWorkflowService.StageFailure(
                            messageId,
                            candidate.conversionId(),
                            candidate.stage(),
                            candidate.stage().name() + "_STAGE_FAILED",
                            true));
            throw exception;
        }
        return 1;
    }

    private record ConversionCoordinate(
            UUID listenerId,
            UUID conversionId,
            long version,
            ConversionWorkflowService.Stage stage) {

        private ConversionCoordinate(UUID listenerId, UUID conversionId, long version) {
            this(listenerId, conversionId, version, ConversionWorkflowService.Stage.SPEECH);
        }
    }
}
