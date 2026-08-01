package dev.audiobook.platform.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.library.PrivateAudiobookLibraryService;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.narration.NarrationReviewService;
import dev.audiobook.platform.narration.NarrationSelectionService;
import dev.audiobook.platform.narration.NarrationSelectionRejectedException;
import dev.audiobook.platform.narration.NarrationRejectionReason;
import dev.audiobook.platform.narration.PublicationNarrationPlanInterpreter;
import dev.audiobook.platform.provider.GovernedSpeechService;
import dev.audiobook.platform.workflow.AudiobookConversionFinalizationService;
import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AudiobookGenerationServiceImpl implements AudiobookGenerationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String REVIEW_SCHEMA = "narration-review-v1";
    private static final String FINAL_MANIFEST_SCHEMA = "audiobook-asset-manifest-v1";

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final NarrationPlanAssetStore narrationPlanAssetStore;
    private final NarrationReviewAssetStore narrationReviewAssetStore;
    private final AudiobookAssetStore audiobookAssetStore;
    private final SpeechSegmentationService segmentationService;
    private final NarrationSelectionService narrationSelectionService;
    private final GovernedSpeechService governedSpeechService;
    private final SpeechResultValidationService validationService;
    private final AudioPackagingService packagingService;
    private final PrivateAudiobookLibraryService privateAudiobookLibraryService;
    private final AudiobookConversionFinalizationService conversionFinalizationService;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final AudioGenerationProperties properties;
    private final Clock identityClock;
    private final ConversionLifecycleService lifecycleService;

    @Override
    public GenerationManifest prepare(UUID listenerId, UUID conversionId) {
        requireIdentity(listenerId, conversionId);
        GenerationManifest replay = existingManifest(listenerId, conversionId);
        if (replay != null) {
            return replay;
        }
        return transaction().execute(status -> prepareLocked(listenerId, conversionId));
    }

    private GenerationManifest prepareLocked(UUID listenerId, UUID conversionId) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0)) IS NULL",
                Boolean.class,
                conversionId.toString());
        GenerationManifest replay = existingManifest(listenerId, conversionId);
        if (replay != null) {
            return replay;
        }
        StoredInputs inputs = generationInputs(listenerId, conversionId);
        if (!"GENERATING".equals(inputs.conversionState())) {
            throw new IllegalStateException("Audiobook Conversion is not ready for speech generation");
        }
        PublicationNarrationPlanInterpreter.NarrationPlan narrationPlan = readNarrationPlan(inputs);
        FrozenReview review = readFrozenReview(inputs);
        List<SpeechSegmentationService.ApprovedChapter> approvedChapters =
                approvedChapters(narrationPlan, review);
        SpeechSegmentationService.Manifest segmented = segmentationService.segment(
                new SpeechSegmentationService.SegmentationRequest(
                        conversionId,
                        inputs.recipeDigest(),
                        inputs.segmentationPolicyVersion(),
                        properties.maximumSegmentCharacters(),
                        approvedChapters));
        UUID manifestId = identifierGenerator.generate();
        Timestamp createdAt = Timestamp.from(identityClock.instant());
        for (SpeechSegmentationService.Segment segment : segmented.segments()) {
            String reference = spokenTextReference(conversionId, segment.segmentId());
            try {
                audiobookAssetStore.writeWorking(
                        reference,
                        segment.spokenText().getBytes(StandardCharsets.UTF_8),
                        "text/plain; charset=utf-8");
            } catch (IOException exception) {
                throw new IllegalStateException("Speech text Working Asset storage is unavailable", exception);
            }
        }
        jdbcTemplate.update(
                """
                INSERT INTO generation.segment_manifest (
                    manifest_id, listener_id, conversion_id, recipe_id, review_decision_id,
                    recipe_digest, narration_plan_digest, segmentation_policy_version,
                    manifest_digest, segment_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                manifestId,
                listenerId,
                conversionId,
                inputs.recipeId(),
                inputs.reviewDecisionId(),
                inputs.recipeDigest(),
                inputs.narrationPlanDigest(),
                inputs.segmentationPolicyVersion(),
                segmented.manifestDigest(),
                segmented.segments().size(),
                createdAt);
        jdbcTemplate.update(
                """
                INSERT INTO generation.active_segment_manifest (
                    conversion_id, listener_id, manifest_id, activated_at
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (conversion_id) DO UPDATE
                SET listener_id = EXCLUDED.listener_id,
                    manifest_id = EXCLUDED.manifest_id,
                    activated_at = EXCLUDED.activated_at
                """,
                conversionId, listenerId, manifestId, createdAt);
        for (SpeechSegmentationService.ApprovedChapter chapter : approvedChapters) {
            jdbcTemplate.update(
                    """
                    INSERT INTO generation.audiobook_chapter_plan (
                        manifest_id, listener_id, conversion_id, chapter_ordinal, display_title
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    manifestId,
                    listenerId,
                    conversionId,
                    chapter.ordinal(),
                    chapter.title());
        }
        for (SpeechSegmentationService.Segment segment : segmented.segments()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO generation.speech_segment (
                        segment_id, manifest_id, listener_id, conversion_id,
                        chapter_ordinal, segment_ordinal, operation_key,
                        spoken_text_ref, spoken_text_sha256, character_count,
                        boundary_kind, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    segment.segmentId(),
                    manifestId,
                    listenerId,
                    conversionId,
                    segment.chapterOrdinal(),
                    segment.segmentOrdinal(),
                    segment.operationKey(),
                    spokenTextReference(conversionId, segment.segmentId()),
                    segment.spokenTextDigest(),
                    segment.characterCount(),
                    segment.boundaryKind().name(),
                    createdAt);
        }
        return manifest(manifestId, segmented.manifestDigest(), segmented.segments());
    }

    @Override
    public AcceptedSegment generateSegment(ProviderCallCommand command) {
        Objects.requireNonNull(command, "command");
        UUID listenerId = command.listenerId();
        UUID conversionId = command.conversionId();
        String operationKey = command.operationKey();
        requireIdentity(listenerId, conversionId);
        Objects.requireNonNull(command.workflowMessageId(), "workflowMessageId");
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 200) {
            throw new IllegalArgumentException("Stable speech operation key is required");
        }
        AcceptedSegment replay = accepted(listenerId, conversionId, operationKey, true);
        if (replay != null) {
            return replay;
        }
        StoredSegment segment = storedSegment(listenerId, conversionId, operationKey);
        NarrationSelectionService.GenerationAuthorization authorization;
        try {
            authorization = narrationSelectionService.authorizeGeneration(listenerId, conversionId);
        } catch (NarrationSelectionRejectedException exception) {
            if (exception.reason() == NarrationRejectionReason.EXPLICIT_NEW_CHOICE_REQUIRED) {
                throw restartUnderFailover(listenerId, conversionId, segment.recipeId());
            }
            throw exception;
        }
        if (!authorization.recipeId().equals(segment.recipeId())
                || !authorization.recipeDigest().equals(segment.recipeDigest())) {
            throw new IllegalStateException("Frozen Generation Recipe is no longer eligible");
        }
        AttemptStart attempt = transaction().execute(status -> startAttempt(
                listenerId,
                conversionId,
                operationKey,
                segment.segmentId(),
                command.workflowMessageId()));
        if (attempt == null) {
            throw new IllegalStateException("Speech provider attempt could not start");
        }
        UUID attemptId = attempt.attemptId();
        try {
            String spokenText = readSpokenText(segment);
            GovernedSpeechService.SpeechOutcome providerOutcome = governedSpeechService.synthesize(
                    new GovernedSpeechService.SpeechCommand(
                            segment.recipeId(), attemptId.toString(), spokenText));
            SpeechProvider.SpeechResult providerResult = providerOutcome.speech();
            String receivedDigest = SpeechSegmentationServiceImpl.sha256Bytes(providerResult.audio());
            transaction().executeWithoutResult(status -> {
                Long estimatedProviderCost = estimatedProviderCostMicros(listenerId, conversionId, operationKey);
                if (estimatedProviderCost != null) {
                    lifecycleService.recordProviderCost(new ConversionLifecycleService.ProviderCost(
                            listenerId,
                            conversionId,
                            estimatedProviderCost,
                            "provider-request:" + providerResult.providerRequestId() + ":attempt:" + attemptId,
                            "provider-cost:" + attemptId));
                }
                jdbcTemplate.update(
                        """
                        UPDATE generation.speech_attempt
                        SET state = 'RECEIVED', provider_request_id = ?, actual_model = ?,
                            actual_region = ?, actual_voice = ?, received_sha256 = ?,
                            capability_profile_version = ?, input_meter = ?, input_units = ?,
                            output_meter = ?, output_units = ?
                        WHERE attempt_id = ?
                        """,
                        providerResult.providerRequestId(),
                        providerResult.actualModel(),
                        providerResult.actualRegion(),
                        providerResult.actualVoice(),
                        receivedDigest,
                        providerOutcome.capabilityProfileVersion(),
                        providerOutcome.usage().inputMeter(),
                        providerOutcome.usage().inputUnits(),
                        providerOutcome.usage().outputMeter(),
                        providerOutcome.usage().outputUnits(),
                        attemptId);
            });
            SpeechResultValidationService.ValidatedPcm pcm = validationService.validate(
                    new SpeechResultValidationService.ExpectedRoute(
                            segment.model(), segment.region(), segment.voice()),
                    providerResult);
            String pcmKey = acceptedPcmReference(conversionId, operationKey, pcm.sha256());
            audiobookAssetStore.writeWorking(pcmKey, pcm.bytes(), "audio/L16;rate=24000;channels=1");
            int accepted = transaction().execute(status -> {
                int inserted = jdbcTemplate.update(
                        """
                        INSERT INTO generation.accepted_segment (
                            operation_key, listener_id, conversion_id, segment_id, attempt_id,
                            recipe_digest, pcm_object_key, pcm_sha256, byte_length,
                            duration_ms, accepted_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_key) DO NOTHING
                        """,
                        operationKey,
                        listenerId,
                        conversionId,
                        segment.segmentId(),
                        attemptId,
                        segment.recipeDigest(),
                        pcmKey,
                        pcm.sha256(),
                        pcm.byteLength(),
                        pcm.durationMs(),
                        Timestamp.from(identityClock.instant()));
                jdbcTemplate.update(
                        """
                        UPDATE generation.speech_attempt
                        SET state = ?, decoded_sha256 = ?, completed_at = ?
                        WHERE attempt_id = ?
                        """,
                        inserted == 1 ? "ACCEPTED" : "DUPLICATE",
                        pcm.sha256(),
                        Timestamp.from(identityClock.instant()),
                        attemptId);
                return inserted;
            });
            AcceptedSegment result = accepted(listenerId, conversionId, operationKey, accepted == 0);
            if (result == null) {
                throw new IllegalStateException("Accepted speech result is unavailable");
            }
            return result;
        } catch (RuntimeException | IOException exception) {
            failAttempt(attemptId, failureCode(exception));
            if (exception instanceof SpeechProviderException providerException
                    && providerException.retryable()) {
                throw restartUnderFailover(listenerId, conversionId, segment.recipeId());
            }
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Speech Working Asset storage is unavailable", exception);
        }
    }

    private GenerationRestartedException restartUnderFailover(
            UUID listenerId, UUID conversionId, UUID failedRecipeId) {
        NarrationSelectionService.FailoverAuthorization failover =
                narrationSelectionService.failoverGeneration(listenerId, conversionId, failedRecipeId);
        GenerationManifest replacement = prepare(listenerId, conversionId);
        return new GenerationRestartedException(
                failover.replacementRecipeId(), replacement.manifestId());
    }

    private AttemptStart startAttempt(
            UUID listenerId,
            UUID conversionId,
            String operationKey,
            String segmentId,
            UUID workflowMessageId) {
        List<String> authorized = jdbcTemplate.queryForList(
                """
                SELECT conversion.state
                FROM workflow.audiobook_conversion conversion
                JOIN workflow.conversion_stage_run stage
                  ON stage.conversion_id = conversion.conversion_id
                 AND stage.stage = 'SPEECH'
                WHERE conversion.listener_id = ? AND conversion.conversion_id = ?
                  AND conversion.state = 'GENERATING'
                  AND stage.state = 'CLAIMED'
                  AND stage.lease_message_id = ?
                  AND stage.lease_expires_at > ?
                FOR UPDATE OF conversion, stage
                """,
                String.class,
                listenerId,
                conversionId,
                workflowMessageId,
                Timestamp.from(identityClock.instant()));
        if (authorized.isEmpty()) {
            throw new IllegalStateException("Audiobook Conversion workflow lease no longer permits provider calls");
        }
        int attemptNumber = jdbcTemplate.queryForObject(
                """
                UPDATE generation.speech_segment
                SET next_attempt_number = next_attempt_number + 1
                WHERE listener_id = ? AND conversion_id = ? AND operation_key = ?
                RETURNING next_attempt_number - 1
                """,
                Integer.class,
                listenerId,
                conversionId,
                operationKey);
        UUID attemptId = identifierGenerator.generate();
        jdbcTemplate.update(
                """
                INSERT INTO generation.speech_attempt (
                    attempt_id, listener_id, conversion_id, segment_id, operation_key,
                    attempt_number, state, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'CALLING_PROVIDER', ?)
                """,
                attemptId,
                listenerId,
                conversionId,
                segmentId,
                operationKey,
                attemptNumber,
                Timestamp.from(identityClock.instant()));
        return new AttemptStart(attemptId);
    }

    private Long estimatedProviderCostMicros(UUID listenerId, UUID conversionId, String operationKey) {
        List<Long> estimates = jdbcTemplate.queryForList(
                """
                SELECT GREATEST(1, CEIL(
                    provider.reserved_delta::numeric * segment.character_count
                    / characters.reserved_delta
                )::bigint)
                FROM generation.speech_segment segment
                JOIN character_entitlement_ledger_entry characters
                  ON characters.listener_id = segment.listener_id
                 AND characters.conversion_id = segment.conversion_id
                 AND characters.entry_type = 'RESERVATION'
                JOIN provider_spend_ledger_entry provider
                  ON provider.reservation_id = characters.reservation_id
                 AND provider.entry_type = 'RESERVATION'
                WHERE segment.listener_id = ? AND segment.conversion_id = ?
                  AND segment.operation_key = ?
                """,
                Long.class,
                listenerId,
                conversionId,
                operationKey);
        return estimates.isEmpty() ? null : estimates.getFirst();
    }

    @Override
    public void packageAudiobook(UUID listenerId, UUID conversionId) {
        requireIdentity(listenerId, conversionId);
        if (storedPreparedFinalization(listenerId, conversionId) != null) {
            return;
        }
        StoredManifest manifest = storedManifest(listenerId, conversionId);
        List<StoredAcceptedPcm> accepted = acceptedPcm(listenerId, conversionId);
        if (accepted.size() != manifest.segmentCount()) {
            throw new IllegalStateException("Speech segment manifest is not complete");
        }
        validatePersistedManifest(manifest);
        validateGapless(accepted, manifest.segmentCount());
        List<AudioPackagingService.Chapter> chapters = packagingChapters(accepted);
        AudioPackagingService.PackagingResult packaged = packagingService.packageAudiobook(
                new AudioPackagingService.PackagingRequest(
                        conversionId,
                        manifest.recipeDigest(),
                        manifest.audioPolicyVersion(),
                        manifest.toolchainVersion(),
                        chapters));
        PreparedFinalization prepared = writeAndVerifyFinalAssets(
                listenerId, conversionId, manifest, packaged);
        persistPreparedFinalization(listenerId, conversionId, prepared);
    }

    @Override
    public PrivateAudiobook finalizeAudiobook(UUID listenerId, UUID conversionId) {
        requireIdentity(listenerId, conversionId);
        PrivateAudiobook replay = privateAudiobook(listenerId, conversionId);
        if (replay != null) {
            return replay;
        }
        PreparedFinalization prepared = storedPreparedFinalization(listenerId, conversionId);
        if (prepared == null) {
            throw new IllegalStateException("Audiobook packaging result is not complete");
        }
        Integer acceptedPackaging = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM workflow.conversion_stage_run stage
                JOIN workflow.conversion_accepted_result accepted
                  ON accepted.stage_run_id = stage.stage_run_id
                JOIN generation.packaged_audiobook_result packaged
                  ON packaged.conversion_id = stage.conversion_id
                 AND packaged.manifest_digest = accepted.result_sha256
                WHERE stage.listener_id = ? AND stage.conversion_id = ?
                  AND stage.stage = 'PACKAGING' AND stage.state = 'SUCCEEDED'
                  AND accepted.operation_key = 'packaging-stage:' || stage.conversion_id
                """,
                Integer.class,
                listenerId,
                conversionId);
        if (acceptedPackaging == null || acceptedPackaging != 1) {
            throw new IllegalStateException("Audiobook packaging result is not authoritatively accepted");
        }
        return transaction().execute(status -> publishFinalization(listenerId, conversionId, prepared));
    }

    private PublicationNarrationPlanInterpreter.NarrationPlan readNarrationPlan(StoredInputs inputs) {
        try {
            byte[] content = narrationPlanAssetStore.read(inputs.conversionId(), inputs.narrationPlanReference());
            requireDigest(content, inputs.narrationPlanDigest(), "Narration Plan");
            return OBJECT_MAPPER.readValue(content, PublicationNarrationPlanInterpreter.NarrationPlan.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Narration Plan Working Asset is unavailable", exception);
        }
    }

    private FrozenReview readFrozenReview(StoredInputs inputs) {
        try {
            byte[] content = narrationReviewAssetStore.read(
                    inputs.conversionId(), inputs.reviewDecisionId(), inputs.reviewReference());
            requireDigest(content, inputs.reviewDigest(), "Narration Review");
            FrozenReview review = OBJECT_MAPPER.readValue(content, FrozenReview.class);
            if (!REVIEW_SCHEMA.equals(review.schemaVersion()) || review.sections().isEmpty()) {
                throw new IllegalStateException("Approved Narration Plan is invalid");
            }
            return review;
        } catch (IOException exception) {
            throw new IllegalStateException("Narration Review Working Asset is unavailable", exception);
        }
    }

    private static List<SpeechSegmentationService.ApprovedChapter> approvedChapters(
            PublicationNarrationPlanInterpreter.NarrationPlan plan, FrozenReview review) {
        Map<Integer, PublicationNarrationPlanInterpreter.Chapter> sourceChapters = new HashMap<>();
        plan.chapters().forEach(chapter -> sourceChapters.put(chapter.ordinal(), chapter));
        Map<ItemKey, PublicationNarrationPlanInterpreter.ReviewItem> reviewItems = new HashMap<>();
        plan.reviewItems().forEach(item -> reviewItems.put(
                new ItemKey(item.chapterOrdinal(), item.ordinal()), item));
        Set<Integer> consumedSourceChapters = new HashSet<>();
        List<SpeechSegmentationService.ApprovedChapter> chapters = new ArrayList<>();
        for (NarrationReviewService.SectionDecision section : review.sections()) {
            if (section.excluded()) {
                section.sourceChapterOrdinals().forEach(consumedSourceChapters::add);
                continue;
            }
            List<SpeechSegmentationService.SpokenUnit> spoken = new ArrayList<>();
            for (int sourceIndex = 0; sourceIndex < section.sourceChapterOrdinals().size(); sourceIndex++) {
                int sourceOrdinal = section.sourceChapterOrdinals().get(sourceIndex);
                if (!consumedSourceChapters.add(sourceOrdinal)) {
                    throw new IllegalStateException("Approved Narration Plan repeats a source chapter");
                }
                PublicationNarrationPlanInterpreter.Chapter source = sourceChapters.get(sourceOrdinal);
                if (source == null) {
                    throw new IllegalStateException("Approved Narration Plan references an unknown chapter");
                }
                Map<ItemKey, NarrationReviewService.ReviewItemDecision> decisions = new HashMap<>();
                section.reviewItems().stream()
                        .filter(item -> item.sourceChapterOrdinal() == sourceOrdinal)
                        .forEach(item -> decisions.put(new ItemKey(sourceOrdinal, item.ordinal()), item));
                List<OrderedSpeech> ordered = new ArrayList<>();
                source.normalProse().forEach(prose -> ordered.add(
                        new OrderedSpeech(prose.sourceOrdinal(), 0, prose.text())));
                plan.reviewItems().stream()
                        .filter(item -> item.chapterOrdinal() == sourceOrdinal)
                        .forEach(item -> {
                            NarrationReviewService.ReviewItemDecision decision =
                                    decisions.get(new ItemKey(sourceOrdinal, item.ordinal()));
                            if (decision == null) {
                                throw new IllegalStateException("Approved Narration Plan omits a review decision");
                            }
                            if (decision.treatment() != NarrationReviewService.Treatment.OMIT) {
                                if (decision.narrationSnippet() == null || decision.narrationSnippet().isBlank()) {
                                    throw new IllegalStateException("Approved narration snippet is empty");
                                }
                                ordered.add(new OrderedSpeech(
                                        item.sourceOrdinal(), 1, decision.narrationSnippet()));
                            }
                        });
                ordered.sort(Comparator.comparingInt(OrderedSpeech::sourceOrdinal)
                        .thenComparingInt(OrderedSpeech::kindOrder));
                ordered.forEach(item -> spoken.add(new SpeechSegmentationService.SpokenUnit(
                        item.text(), SpeechSegmentationService.BoundaryKind.PARAGRAPH)));
                if (!spoken.isEmpty() && sourceIndex + 1 < section.sourceChapterOrdinals().size()) {
                    replaceLastBoundary(spoken, SpeechSegmentationService.BoundaryKind.STRUCTURAL_SECTION);
                }
            }
            if (spoken.isEmpty()) {
                throw new IllegalStateException("An included audiobook chapter has no approved speech");
            }
            replaceLastBoundary(spoken, SpeechSegmentationService.BoundaryKind.CHAPTER);
            chapters.add(new SpeechSegmentationService.ApprovedChapter(
                    chapters.size(), section.title(), spoken));
        }
        if (!consumedSourceChapters.equals(sourceChapters.keySet()) || chapters.isEmpty()) {
            throw new IllegalStateException("Approved Narration Plan is incomplete");
        }
        return List.copyOf(chapters);
    }

    private static void replaceLastBoundary(
            List<SpeechSegmentationService.SpokenUnit> spoken,
            SpeechSegmentationService.BoundaryKind boundary) {
        SpeechSegmentationService.SpokenUnit last = spoken.removeLast();
        spoken.add(new SpeechSegmentationService.SpokenUnit(last.text(), boundary));
    }

    private StoredInputs generationInputs(UUID listenerId, UUID conversionId) {
        List<StoredInputs> matches = jdbcTemplate.query(
                """
                SELECT ac.state, ac.conversion_id, gr.recipe_id, gr.recipe_digest,
                       gr.segmentation_policy_version,
                       np.working_asset_ref AS plan_ref, np.asset_sha256 AS plan_digest,
                       rd.decision_id, rd.working_asset_ref AS review_ref,
                       rd.asset_sha256 AS review_digest
                FROM workflow.audiobook_conversion ac
                JOIN narration.generation_recipe gr
                  ON gr.recipe_id = ac.current_generation_recipe_id
                JOIN narration.narration_plan np ON np.conversion_id = ac.conversion_id
                JOIN narration.narration_review_decision rd ON rd.conversion_id = ac.conversion_id
                WHERE ac.listener_id = ? AND ac.conversion_id = ?
                """,
                (resultSet, row) -> new StoredInputs(
                        resultSet.getString("state"),
                        resultSet.getObject("conversion_id", UUID.class),
                        resultSet.getObject("recipe_id", UUID.class),
                        resultSet.getString("recipe_digest"),
                        resultSet.getString("segmentation_policy_version"),
                        resultSet.getString("plan_ref"),
                        resultSet.getString("plan_digest"),
                        resultSet.getObject("decision_id", UUID.class),
                        resultSet.getString("review_ref"),
                        resultSet.getString("review_digest")),
                listenerId,
                conversionId);
        if (matches.isEmpty()) {
            throw new IllegalStateException("Approved generation inputs are unavailable");
        }
        return matches.getFirst();
    }

    private StoredSegment storedSegment(UUID listenerId, UUID conversionId, String operationKey) {
        List<StoredSegment> matches = jdbcTemplate.query(
                """
                SELECT s.segment_id, s.spoken_text_ref, s.spoken_text_sha256,
                       gr.recipe_id, m.recipe_digest, gr.model_snapshot, gr.region,
                       gr.provider_voice
                FROM generation.speech_segment s
                JOIN generation.segment_manifest m ON m.manifest_id = s.manifest_id
                JOIN generation.active_segment_manifest active ON active.manifest_id = m.manifest_id
                JOIN narration.generation_recipe gr ON gr.recipe_id = m.recipe_id
                WHERE s.listener_id = ? AND s.conversion_id = ? AND s.operation_key = ?
                """,
                (resultSet, row) -> new StoredSegment(
                        resultSet.getString("segment_id"),
                        resultSet.getString("spoken_text_ref"),
                        resultSet.getString("spoken_text_sha256"),
                        resultSet.getObject("recipe_id", UUID.class),
                        resultSet.getString("recipe_digest"),
                        resultSet.getString("model_snapshot"),
                        resultSet.getString("region"),
                        resultSet.getString("provider_voice")),
                listenerId,
                conversionId,
                operationKey);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Speech operation is unavailable");
        }
        return matches.getFirst();
    }

    private String readSpokenText(StoredSegment segment) throws IOException {
        byte[] content = audiobookAssetStore.readWorking(segment.spokenTextReference());
        requireDigest(content, segment.spokenTextDigest(), "Speech text");
        return new String(content, StandardCharsets.UTF_8);
    }

    private void failAttempt(UUID attemptId, String code) {
        jdbcTemplate.update(
                """
                UPDATE generation.speech_attempt
                SET state = 'FAILED', error_code = ?, completed_at = ?
                WHERE attempt_id = ? AND state <> 'ACCEPTED'
                """,
                code,
                Timestamp.from(identityClock.instant()),
                attemptId);
    }

    private static String failureCode(Exception exception) {
        if (exception instanceof SpeechProviderException provider) {
            return provider.code().name();
        }
        if (exception instanceof SpeechValidationException validation) {
            return validation.code().name();
        }
        if (exception instanceof IOException) {
            return "WORKING_ASSET_UNAVAILABLE";
        }
        return "GENERATION_FAILURE";
    }

    private AcceptedSegment accepted(
            UUID listenerId, UUID conversionId, String operationKey, boolean replayed) {
        List<AcceptedSegment> matches = jdbcTemplate.query(
                """
                SELECT accepted.operation_key, accepted.attempt_id,
                       accepted.pcm_sha256, accepted.duration_ms
                FROM generation.accepted_segment accepted
                JOIN generation.speech_segment segment
                  ON segment.operation_key = accepted.operation_key
                JOIN generation.active_segment_manifest active
                  ON active.manifest_id = segment.manifest_id
                WHERE accepted.listener_id = ? AND accepted.conversion_id = ?
                  AND accepted.operation_key = ?
                """,
                (resultSet, row) -> new AcceptedSegment(
                        resultSet.getString("operation_key"),
                        resultSet.getObject("attempt_id", UUID.class),
                        resultSet.getString("pcm_sha256"),
                        resultSet.getLong("duration_ms"),
                        replayed),
                listenerId,
                conversionId,
                operationKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private StoredManifest storedManifest(UUID listenerId, UUID conversionId) {
        List<StoredManifest> matches = jdbcTemplate.query(
                """
                SELECT m.manifest_id, m.recipe_id, m.recipe_digest, m.manifest_digest,
                       m.segment_count, m.created_at, m.segmentation_policy_version,
                       gr.audio_policy_version, gr.toolchain_version
                FROM generation.segment_manifest m
                JOIN generation.active_segment_manifest active ON active.manifest_id = m.manifest_id
                JOIN narration.generation_recipe gr ON gr.recipe_id = m.recipe_id
                WHERE m.listener_id = ? AND m.conversion_id = ?
                """,
                (resultSet, row) -> new StoredManifest(
                        resultSet.getObject("manifest_id", UUID.class),
                        resultSet.getObject("recipe_id", UUID.class),
                        resultSet.getString("recipe_digest"),
                        resultSet.getString("manifest_digest"),
                        resultSet.getInt("segment_count"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getString("segmentation_policy_version"),
                        resultSet.getString("audio_policy_version"),
                        resultSet.getString("toolchain_version")),
                listenerId,
                conversionId);
        if (matches.isEmpty()) {
            throw new IllegalStateException("Speech segment manifest is unavailable");
        }
        return matches.getFirst();
    }

    private List<StoredAcceptedPcm> acceptedPcm(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.query(
                """
                SELECT s.chapter_ordinal, s.segment_ordinal, s.boundary_kind,
                       cp.display_title, a.pcm_object_key, a.pcm_sha256,
                       a.byte_length, a.duration_ms
                FROM generation.speech_segment s
                JOIN generation.audiobook_chapter_plan cp
                  ON cp.manifest_id = s.manifest_id AND cp.chapter_ordinal = s.chapter_ordinal
                JOIN generation.accepted_segment a ON a.operation_key = s.operation_key
                JOIN generation.active_segment_manifest active ON active.manifest_id = s.manifest_id
                WHERE s.listener_id = ? AND s.conversion_id = ?
                ORDER BY s.chapter_ordinal, s.segment_ordinal
                """,
                (resultSet, row) -> new StoredAcceptedPcm(
                        resultSet.getInt("chapter_ordinal"),
                        resultSet.getInt("segment_ordinal"),
                        SpeechSegmentationService.BoundaryKind.valueOf(resultSet.getString("boundary_kind")),
                        resultSet.getString("display_title"),
                        resultSet.getString("pcm_object_key"),
                        resultSet.getString("pcm_sha256"),
                        resultSet.getLong("byte_length"),
                        resultSet.getLong("duration_ms")),
                listenerId,
                conversionId);
    }

    private void validatePersistedManifest(StoredManifest manifest) {
        List<SpeechSegmentationService.ManifestEntry> entries = jdbcTemplate.query(
                """
                SELECT chapter_ordinal, segment_ordinal, spoken_text_sha256,
                       boundary_kind, character_count
                FROM generation.speech_segment
                WHERE manifest_id = ?
                ORDER BY chapter_ordinal, segment_ordinal
                """,
                (resultSet, row) -> new SpeechSegmentationService.ManifestEntry(
                        resultSet.getInt("chapter_ordinal"),
                        resultSet.getInt("segment_ordinal"),
                        resultSet.getString("spoken_text_sha256"),
                        SpeechSegmentationService.BoundaryKind.valueOf(resultSet.getString("boundary_kind")),
                        resultSet.getInt("character_count")),
                manifest.manifestId());
        String actual = segmentationService.manifestDigest(manifest.segmentationPolicyVersion(), entries);
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                manifest.manifestDigest().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Persisted speech segment manifest integrity check failed");
        }
    }

    private static void validateGapless(List<StoredAcceptedPcm> accepted, int expectedCount) {
        if (accepted.size() != expectedCount) {
            throw new IllegalStateException("Speech segment manifest is not complete");
        }
        int chapter = 0;
        int segment = 0;
        for (StoredAcceptedPcm pcm : accepted) {
            if (pcm.chapterOrdinal() == chapter + 1) {
                chapter++;
                segment = 0;
            }
            if (pcm.chapterOrdinal() != chapter || pcm.segmentOrdinal() != segment++) {
                throw new IllegalStateException("Speech segment manifest is not gapless");
            }
        }
    }

    private List<AudioPackagingService.Chapter> packagingChapters(List<StoredAcceptedPcm> accepted) {
        Map<Integer, List<StoredAcceptedPcm>> grouped = new LinkedHashMap<>();
        accepted.forEach(pcm -> grouped.computeIfAbsent(pcm.chapterOrdinal(), ignored -> new ArrayList<>()).add(pcm));
        List<AudioPackagingService.Chapter> chapters = new ArrayList<>();
        grouped.forEach((ordinal, segments) -> chapters.add(new AudioPackagingService.Chapter(
                ordinal,
                segments.getFirst().displayTitle(),
                segments.stream().map(segment -> new AudioPackagingService.AcceptedPcm(
                                verifiedPcm(segment), segment.boundaryKind()))
                        .toList())));
        return List.copyOf(chapters);
    }

    private byte[] verifiedPcm(StoredAcceptedPcm segment) {
        try {
            byte[] content = audiobookAssetStore.readWorking(segment.objectKey());
            requireDigest(content, segment.sha256(), "Accepted speech");
            if (content.length != segment.byteLength()
                    || Math.multiplyExact(content.length / 2L, 1_000L) / properties.sampleRate()
                            != segment.durationMs()) {
                throw new IllegalStateException("Accepted speech duration or length does not reconcile");
            }
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException("Accepted speech Working Asset is unavailable", exception);
        }
    }

    private PreparedFinalization writeAndVerifyFinalAssets(
            UUID listenerId,
            UUID conversionId,
            StoredManifest manifest,
            AudioPackagingService.PackagingResult packaged) {
        UUID audiobookId = stableUuid("audiobook:" + conversionId);
        UUID assetVersionId = stableUuid("asset:" + conversionId + ":" + manifest.recipeDigest());
        List<PreparedChapter> chapters = new ArrayList<>();
        for (AudioPackagingService.PackagedChapter chapter : packaged.chapters()) {
            UUID chapterId = stableUuid("chapter:" + assetVersionId + ":" + chapter.ordinal());
            List<PreparedPart> parts = new ArrayList<>();
            for (AudioPackagingService.PackagedPart part : chapter.parts()) {
                UUID partId = stableUuid(
                        "part:" + assetVersionId + ":" + chapter.ordinal() + ":" + part.ordinal());
                String key = "audiobooks/" + audiobookId + "/assets/" + assetVersionId
                        + "/chapters/" + chapter.ordinal() + "/parts/" + part.ordinal()
                        + "-" + part.sha256() + ".mp3";
                writeVerifiedFinal(key, part.bytes(), part.sha256(), part.byteLength(), "audio/mpeg");
                parts.add(new PreparedPart(
                        partId, part.ordinal(), key, part.mimeType(),
                        part.byteLength(), part.durationMs(), part.sha256()));
            }
            chapters.add(new PreparedChapter(
                    chapterId,
                    chapter.ordinal(),
                    chapter.displayTitle(),
                    chapter.startMs(),
                    chapter.durationMs(),
                    parts));
        }
        FinalManifest finalManifest = new FinalManifest(
                FINAL_MANIFEST_SCHEMA,
                audiobookId,
                assetVersionId,
                manifest.recipeDigest(),
                packaged.profileVersion(),
                packaged.totalDurationMs(),
                packaged.totalBytes(),
                manifest.createdAt(),
                chapters);
        byte[] manifestBytes;
        try {
            manifestBytes = OBJECT_MAPPER.writeValueAsBytes(finalManifest);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Final Audiobook Asset manifest is invalid", exception);
        }
        String digest = SpeechSegmentationServiceImpl.sha256Bytes(manifestBytes);
        String manifestKey = "audiobooks/" + audiobookId + "/assets/" + assetVersionId
                + "/manifest-" + digest + ".json";
        writeVerifiedFinal(manifestKey, manifestBytes, digest, manifestBytes.length, "application/json");
        return new PreparedFinalization(
                audiobookId,
                assetVersionId,
                manifest.recipeId(),
                manifest.recipeDigest(),
                manifestKey,
                digest,
                packaged,
                chapters);
    }

    private void writeVerifiedFinal(
            String key, byte[] content, String digest, long byteLength, String contentType) {
        try {
            AudiobookAssetStore.StoredAsset stored =
                    audiobookAssetStore.writeFinal(key, content, contentType);
            byte[] verified = audiobookAssetStore.readFinal(key);
            if (!stored.sha256().equals(digest)
                    || stored.byteLength() != byteLength
                    || verified.length != byteLength
                    || !MessageDigest.isEqual(verified, content)) {
                throw new IllegalStateException("Final Audiobook Asset integrity check failed");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Final Audiobook Asset storage is unavailable", exception);
        }
    }

    private PrivateAudiobook publishFinalization(
            UUID listenerId, UUID conversionId, PreparedFinalization prepared) {
        PrivateAudiobook replay = privateAudiobook(listenerId, conversionId);
        if (replay != null) {
            return replay;
        }
        conversionFinalizationService.beginFinalizing(listenerId, conversionId);
        conversionFinalizationService.lockAndRequireFinalizing(listenerId, conversionId);
        Instant now = identityClock.instant();
        AudioPackagingService.PackagingResult packaged = prepared.packaged();
        privateAudiobookLibraryService.publish(new PrivateAudiobookLibraryService.Publication(
                listenerId,
                conversionId,
                prepared.audiobookId(),
                prepared.assetVersionId(),
                prepared.recipeId(),
                prepared.recipeDigest(),
                prepared.manifestObjectKey(),
                prepared.manifestDigest(),
                packaged.profileVersion(),
                packaged.totalDurationMs(),
                packaged.totalBytes(),
                packaged.integratedLoudnessLufs(),
                packaged.truePeakDbtp(),
                packaged.appliedGainDb(),
                now,
                prepared.chapters().stream()
                        .map(chapter -> new PrivateAudiobookLibraryService.Chapter(
                                chapter.chapterId(),
                                chapter.ordinal(),
                                chapter.displayTitle(),
                                chapter.startMs(),
                                chapter.durationMs(),
                                chapter.parts().stream()
                                        .map(part -> new PrivateAudiobookLibraryService.Part(
                                                part.partId(),
                                                part.ordinal(),
                                                part.objectKey(),
                                                part.mimeType(),
                                                part.byteLength(),
                                                part.durationMs(),
                                                part.sha256()))
                                        .toList()))
                        .toList()));
        conversionFinalizationService.markFinalized(listenerId, conversionId);
        jdbcTemplate.update(
                """
                INSERT INTO generation.working_asset_erasure_obligation (
                    obligation_id, listener_id, conversion_id, begins_at, erase_by, state
                ) VALUES (?, ?, ?, ?, ?, 'PENDING')
                """,
                identifierGenerator.generate(),
                listenerId,
                conversionId,
                Timestamp.from(now),
                Timestamp.from(now.plus(properties.workingAssetRetention())));
        return new PrivateAudiobook(
                prepared.audiobookId(),
                prepared.assetVersionId(),
                "AVAILABLE",
                prepared.manifestDigest(),
                packaged.totalDurationMs());
    }

    private void persistPreparedFinalization(
            UUID listenerId, UUID conversionId, PreparedFinalization prepared) {
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO generation.packaged_audiobook_result (
                        conversion_id, listener_id, manifest_digest, result_json, created_at
                    ) VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                    ON CONFLICT (conversion_id) DO NOTHING
                    """,
                    conversionId,
                    listenerId,
                    prepared.manifestDigest(),
                    OBJECT_MAPPER.writeValueAsString(prepared),
                    Timestamp.from(identityClock.instant()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Packaging result checkpoint is invalid", exception);
        }
    }

    private PreparedFinalization storedPreparedFinalization(UUID listenerId, UUID conversionId) {
        List<String> results = jdbcTemplate.query(
                """
                SELECT result_json::text
                FROM generation.packaged_audiobook_result
                WHERE listener_id = ? AND conversion_id = ?
                """,
                (resultSet, row) -> resultSet.getString(1),
                listenerId,
                conversionId);
        if (results.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(results.getFirst(), PreparedFinalization.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Packaging result checkpoint is invalid", exception);
        }
    }

    private PrivateAudiobook privateAudiobook(UUID listenerId, UUID conversionId) {
        PrivateAudiobookLibraryService.PrivateAudiobook audiobook =
                privateAudiobookLibraryService.find(listenerId, conversionId);
        return audiobook == null
                ? null
                : new PrivateAudiobook(
                        audiobook.audiobookId(),
                        audiobook.assetVersionId(),
                        audiobook.availability(),
                        audiobook.manifestDigest(),
                        audiobook.totalDurationMs());
    }

    private GenerationManifest existingManifest(UUID listenerId, UUID conversionId) {
        List<GenerationManifest> matches = jdbcTemplate.query(
                """
                SELECT manifest.manifest_id, manifest.manifest_digest
                FROM generation.segment_manifest manifest
                JOIN generation.active_segment_manifest active
                  ON active.manifest_id = manifest.manifest_id
                JOIN workflow.audiobook_conversion conversion
                  ON conversion.conversion_id = manifest.conversion_id
                WHERE manifest.listener_id = ? AND manifest.conversion_id = ?
                  AND conversion.current_generation_recipe_id = manifest.recipe_id
                """,
                (resultSet, row) -> new GenerationManifest(
                        resultSet.getObject("manifest_id", UUID.class),
                        resultSet.getString("manifest_digest"),
                        List.of()),
                listenerId,
                conversionId);
        if (matches.isEmpty()) {
            return null;
        }
        GenerationManifest header = matches.getFirst();
        return new GenerationManifest(
                header.manifestId(), header.manifestDigest(), manifestSegments(header.manifestId()));
    }

    private List<Segment> manifestSegments(UUID manifestId) {
        return jdbcTemplate.query(
                """
                SELECT segment_id, operation_key, chapter_ordinal, segment_ordinal,
                       spoken_text_sha256, boundary_kind
                FROM generation.speech_segment
                WHERE manifest_id = ?
                ORDER BY chapter_ordinal, segment_ordinal
                """,
                (resultSet, row) -> new Segment(
                        resultSet.getString("segment_id"),
                        resultSet.getString("operation_key"),
                        resultSet.getInt("chapter_ordinal"),
                        resultSet.getInt("segment_ordinal"),
                        resultSet.getString("spoken_text_sha256"),
                        SpeechSegmentationService.BoundaryKind.valueOf(resultSet.getString("boundary_kind"))),
                manifestId);
    }

    private static GenerationManifest manifest(
            UUID manifestId,
            String manifestDigest,
            List<SpeechSegmentationService.Segment> segments) {
        return new GenerationManifest(
                manifestId,
                manifestDigest,
                segments.stream()
                        .map(segment -> new Segment(
                                segment.segmentId(),
                                segment.operationKey(),
                                segment.chapterOrdinal(),
                                segment.segmentOrdinal(),
                                segment.spokenTextDigest(),
                                segment.boundaryKind()))
                        .toList());
    }

    private static void requireDigest(byte[] content, String expected, String assetKind) {
        String actual = SpeechSegmentationServiceImpl.sha256Bytes(content);
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException(assetKind + " Working Asset integrity check failed");
        }
    }

    private static void requireIdentity(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static String spokenTextReference(UUID conversionId, String segmentId) {
        return "conversions/" + conversionId + "/speech/text/" + segmentId + ".txt";
    }

    private static String acceptedPcmReference(UUID conversionId, String operationKey, String digest) {
        return "conversions/" + conversionId + "/speech/pcm/"
                + SpeechSegmentationServiceImpl.sha256(operationKey) + "-" + digest + ".pcm";
    }

    private static UUID stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record FrozenReview(
            String schemaVersion,
            NarrationReviewService.ReviewAction action,
            List<NarrationReviewService.SectionDecision> sections) {
        private FrozenReview {
            sections = List.copyOf(sections);
        }
    }

    private record ItemKey(int sourceChapterOrdinal, int ordinal) {
    }

    private record OrderedSpeech(int sourceOrdinal, int kindOrder, String text) {
    }

    private record StoredInputs(
            String conversionState,
            UUID conversionId,
            UUID recipeId,
            String recipeDigest,
            String segmentationPolicyVersion,
            String narrationPlanReference,
            String narrationPlanDigest,
            UUID reviewDecisionId,
            String reviewReference,
            String reviewDigest) {
    }

    private record StoredSegment(
            String segmentId,
            String spokenTextReference,
            String spokenTextDigest,
            UUID recipeId,
            String recipeDigest,
            String model,
            String region,
            String voice) {
    }

    private record AttemptStart(UUID attemptId) {
    }

    private record StoredManifest(
            UUID manifestId,
            UUID recipeId,
            String recipeDigest,
            String manifestDigest,
            int segmentCount,
            Instant createdAt,
            String segmentationPolicyVersion,
            String audioPolicyVersion,
            String toolchainVersion) {
    }

    private record StoredAcceptedPcm(
            int chapterOrdinal,
            int segmentOrdinal,
            SpeechSegmentationService.BoundaryKind boundaryKind,
            String displayTitle,
            String objectKey,
            String sha256,
            long byteLength,
            long durationMs) {
    }

    private record PreparedFinalization(
            UUID audiobookId,
            UUID assetVersionId,
            UUID recipeId,
            String recipeDigest,
            String manifestObjectKey,
            String manifestDigest,
            AudioPackagingService.PackagingResult packaged,
            List<PreparedChapter> chapters) {
    }

    private record PreparedChapter(
            UUID chapterId,
            int ordinal,
            String displayTitle,
            long startMs,
            long durationMs,
            List<PreparedPart> parts) {
    }

    private record PreparedPart(
            UUID partId,
            int ordinal,
            String objectKey,
            String mimeType,
            long byteLength,
            long durationMs,
            String sha256) {
    }

    private record FinalManifest(
            String schemaVersion,
            UUID audiobookId,
            UUID assetVersionId,
            String recipeDigest,
            String packagingProfileVersion,
            long totalDurationMs,
            long totalBytes,
            Instant createdAt,
            List<PreparedChapter> chapters) {
    }
}
