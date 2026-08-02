package dev.audiobook.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.generation.assets.AudiobookAssetStore;
import dev.audiobook.platform.generation.service.AudiobookGenerationService;
import dev.audiobook.platform.generation.service.AudiobookGenerationWorkerService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.listener.service.ListenerIdentityService;
import dev.audiobook.platform.identity.signin.ExternalIdentity;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationRejectionReason;
import dev.audiobook.platform.narration.PublicationNarrationPlanInterpreter;
import dev.audiobook.platform.narration.review.service.NarrationReviewService;
import dev.audiobook.platform.narration.selection.error.exception.NarrationSelectionRejectedException;
import dev.audiobook.platform.narration.selection.service.NarrationSelectionService;
import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.SpeechProviderException;
import dev.audiobook.platform.provider.speech.ProviderSpeechAdapter;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
class AudiobookGenerationITest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final UUID ROWAN_ID = UUID.fromString("10000000-0000-7000-8000-000000000001");
    private static final FfmpegTestToolchain FFMPEG = startFfmpeg();

    private final AudiobookGenerationService generationService;
    private final ListenerIdentityService listenerIdentityService;
    private final NarrationReviewService narrationReviewService;
    private final NarrationSelectionService narrationSelectionService;
    private final AudiobookConversionService conversionService;
    private final NarrationPlanAssetStore planAssetStore;
    private final AudiobookAssetStore audiobookAssetStore;
    private final AudiobookGenerationWorkerService workerService;
    private final JdbcTemplate jdbcTemplate;
    private final ConversionWorkflowService workflowService;

    @MockitoBean(name = "openAiProviderSpeechAdapterImpl")
    private ProviderSpeechAdapter openAiSpeechAdapter;

    @MockitoBean(name = "googleProviderSpeechAdapterImpl")
    private ProviderSpeechAdapter googleSpeechAdapter;

    @Autowired
    AudiobookGenerationITest(
            AudiobookGenerationService generationService,
            ListenerIdentityService listenerIdentityService,
            NarrationReviewService narrationReviewService,
            NarrationSelectionService narrationSelectionService,
            AudiobookConversionService conversionService,
            NarrationPlanAssetStore planAssetStore,
            AudiobookAssetStore audiobookAssetStore,
            AudiobookGenerationWorkerService workerService,
            JdbcTemplate jdbcTemplate,
            ConversionWorkflowService workflowService) {
        this.generationService = generationService;
        this.listenerIdentityService = listenerIdentityService;
        this.narrationReviewService = narrationReviewService;
        this.narrationSelectionService = narrationSelectionService;
        this.conversionService = conversionService;
        this.planAssetStore = planAssetStore;
        this.audiobookAssetStore = audiobookAssetStore;
        this.workerService = workerService;
        this.jdbcTemplate = jdbcTemplate;
        this.workflowService = workflowService;
    }

    @BeforeEach
    void identifyProviderAdapters() {
        given(openAiSpeechAdapter.provider()).willReturn("openai");
        given(googleSpeechAdapter.provider()).willReturn("google");
    }

    @Test
    void approvedPlanFinalizesExactlyOnceDespiteReverseCompletionAndRedelivery() throws Exception {
        Conversion conversion = approvedGeneratingConversion("complete");
        given(openAiSpeechAdapter.synthesize(any()))
                .willAnswer(
                        invocation -> {
                            ProviderSpeechAdapter.SpeechRequest request = invocation.getArgument(0);
                            byte[] audio =
                                    wav(
                                            sine(
                                                    3_000,
                                                    220
                                                            + Math.abs(
                                                                    request.canonicalText()
                                                                                    .hashCode()
                                                                            % 200)));
                            return speechResult(
                                    "provider-" + request.canonicalText().hashCode(),
                                    "gpt-4o-mini-tts-2025-12-15",
                                    "eu",
                                    "cedar",
                                    audio);
                        });

        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());
        List<AudiobookGenerationService.Segment> reverse = manifest.segments().reversed();
        for (AudiobookGenerationService.Segment segment : reverse) {
            generateSegment(conversion, segment.operationKey());
        }
        AudiobookGenerationService.AcceptedSegment replay =
                generateSegment(conversion, reverse.getFirst().operationKey());
        acceptSpeechStage(conversion, manifest);

        assertThat(workerService.packagePending()).isOne();
        assertThat(workerService.packagePending()).isOne();
        AudiobookGenerationService.PrivateAudiobook finalized =
                generationService.finalizeAudiobook(
                        conversion.listenerId(), conversion.conversionId());
        AudiobookGenerationService.PrivateAudiobook finalizationReplay =
                generationService.finalizeAudiobook(
                        conversion.listenerId(), conversion.conversionId());

        assertThat(replay.replayed()).isTrue();
        assertThat(finalizationReplay).isEqualTo(finalized);
        assertThat(manifest.segments())
                .extracting(
                        AudiobookGenerationService.Segment::chapterOrdinal,
                        AudiobookGenerationService.Segment::segmentOrdinal)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, 0),
                        org.assertj.core.groups.Tuple.tuple(0, 1));
        assertThat(finalized.availability()).isEqualTo("AVAILABLE");
        assertThat(
                        conversionService
                                .conversion(conversion.listenerId(), conversion.conversionId())
                                .state())
                .isEqualTo(AudiobookConversionService.ConversionState.FINALIZED);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM generation.speech_attempt WHERE conversion_id"
                                        + " = ?",
                                Integer.class,
                                conversion.conversionId()))
                .isEqualTo(2);
        assertThat(
                        jdbcTemplate.queryForList(
                                """
                                SELECT evidence.capability_profile_version, evidence.model_evidence_source,
                                       evidence.input_units,
                                       evidence.output_units, evidence.generation_recipe_id
                                FROM provider.operation_evidence evidence
                                WHERE evidence.generation_recipe_id = ?
                                """,
                                conversion.recipeId()))
                .hasSize(2)
                .allSatisfy(
                        evidence -> {
                            assertThat(evidence.get("capability_profile_version"))
                                    .isEqualTo("openai-speech-eu-v2");
                            assertThat(evidence.get("model_evidence_source"))
                                    .isEqualTo("REQUESTED_MODEL");
                            assertThat((Long) evidence.get("input_units")).isPositive();
                            assertThat((Long) evidence.get("output_units")).isPositive();
                        });
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM generation.accepted_segment WHERE"
                                        + " conversion_id = ?",
                                Integer.class,
                                conversion.conversionId()))
                .isEqualTo(2);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM library.private_audiobook WHERE conversion_id"
                                        + " = ? AND availability = 'AVAILABLE'",
                                Integer.class,
                                conversion.conversionId()))
                .isOne();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM generation.working_asset_erasure_obligation"
                                        + " WHERE conversion_id = ?",
                                Integer.class,
                                conversion.conversionId()))
                .isOne();
        String manifestKey =
                jdbcTemplate.queryForObject(
                        "SELECT manifest_object_key FROM library.audiobook_asset_version WHERE"
                                + " asset_version_id = ?",
                        String.class,
                        finalized.assetVersionId());
        assertThat(audiobookAssetStore.readFinal(manifestKey)).isNotEmpty();
        verify(openAiSpeechAdapter, times(2)).synthesize(any());
    }

    @Test
    void retryableProviderFailureRestartsEverySegmentUnderOneEquivalentRecipe() throws Exception {
        Conversion conversion = approvedGeneratingConversion("qualified-failover");
        AudiobookGenerationService.GenerationManifest original =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());
        AtomicInteger calls = new AtomicInteger();
        given(openAiSpeechAdapter.synthesize(any()))
                .willAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() == 2) {
                                throw new SpeechProviderException(
                                        SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true);
                            }
                            return speechResult(
                                    "provider-primary",
                                    "gpt-4o-mini-tts-2025-12-15",
                                    "eu",
                                    "cedar",
                                    wav(sine(1_000, 220)));
                        });

        generateSegment(conversion, original.segments().getFirst().operationKey());
        assertThatThrownBy(
                        () ->
                                generateSegment(
                                        conversion, original.segments().get(1).operationKey()))
                .isInstanceOf(GenerationRestartedException.class);

        AudiobookGenerationService.GenerationManifest replacement =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());
        assertThat(replacement.manifestId()).isNotEqualTo(original.manifestId());
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT provider FROM narration.generation_recipe recipe
                                JOIN generation.segment_manifest manifest ON manifest.recipe_id = recipe.recipe_id
                                JOIN generation.active_segment_manifest active ON active.manifest_id = manifest.manifest_id
                                WHERE active.conversion_id = ?
                                """,
                                String.class,
                                conversion.conversionId()))
                .isEqualTo("google");
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT count(*) FROM generation.accepted_segment accepted
                                JOIN generation.speech_segment segment ON segment.operation_key = accepted.operation_key
                                JOIN generation.active_segment_manifest active ON active.manifest_id = segment.manifest_id
                                WHERE active.conversion_id = ?
                                """,
                                Integer.class,
                                conversion.conversionId()))
                .isZero();

        given(googleSpeechAdapter.synthesize(any()))
                .willReturn(
                        speechResult(
                                "provider-failover",
                                "Neural2",
                                "eu",
                                "en-GB-Neural2-F",
                                wav(sine(1_000, 330))));
        for (AudiobookGenerationService.Segment segment : replacement.segments()) {
            generateSegment(conversion, segment.operationKey());
        }

        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT count(DISTINCT attempt.capability_profile_version)
                                FROM generation.speech_attempt attempt
                                JOIN generation.speech_segment segment ON segment.segment_id = attempt.segment_id
                                JOIN generation.active_segment_manifest active ON active.manifest_id = segment.manifest_id
                                WHERE active.conversion_id = ? AND attempt.state = 'ACCEPTED'
                                """,
                                Integer.class,
                                conversion.conversionId()))
                .isOne();
    }

    @Test
    void failoverIsRejectedWhenEquivalenceEvaluationIsStale() throws Exception {
        Conversion conversion = approvedGeneratingConversion("stale-equivalence");
        jdbcTemplate.update(
                """
                UPDATE narration.qualified_voice_equivalence equivalence
                SET evaluation_state = 'STALE'
                FROM narration.generation_recipe recipe
                WHERE recipe.recipe_id = ?
                  AND equivalence.primary_mapping_id = recipe.voice_mapping_id
                """,
                conversion.recipeId());
        try {
            assertThatThrownBy(
                            () ->
                                    narrationSelectionService.failoverGeneration(
                                            conversion.listenerId(),
                                            conversion.conversionId(),
                                            conversion.recipeId()))
                    .isInstanceOf(NarrationSelectionRejectedException.class)
                    .extracting(
                            exception -> ((NarrationSelectionRejectedException) exception).reason())
                    .isEqualTo(NarrationRejectionReason.QUALIFIED_FAILOVER_UNAVAILABLE);
        } finally {
            jdbcTemplate.update(
                    "UPDATE narration.qualified_voice_equivalence SET evaluation_state ="
                            + " 'QUALIFIED'");
        }
    }

    @Test
    void missingOrInvalidSpeechNeverExposesAPartialPrivateAudiobook() throws Exception {
        Conversion missing = approvedGeneratingConversion("missing");
        generationService.prepare(missing.listenerId(), missing.conversionId());

        assertThatThrownBy(
                        () ->
                                generationService.finalizeAudiobook(
                                        missing.listenerId(), missing.conversionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not complete");
        assertThat(audiobookCount(missing.conversionId())).isZero();

        Conversion drift = approvedGeneratingConversion("drift");
        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(drift.listenerId(), drift.conversionId());
        given(openAiSpeechAdapter.synthesize(any()))
                .willReturn(
                        speechResult(
                                "provider-drift",
                                "unapproved-model",
                                "eu",
                                "cedar",
                                wav(sine(3_000, 220))));

        assertThatThrownBy(
                        () -> generateSegment(drift, manifest.segments().getFirst().operationKey()))
                .isInstanceOf(SpeechProviderException.class);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM generation.speech_attempt WHERE conversion_id ="
                                        + " ?",
                                String.class,
                                drift.conversionId()))
                .isEqualTo("FAILED");
        assertThat(audiobookCount(drift.conversionId())).isZero();
    }

    @Test
    void providerCallRequiresTheActiveAuthoritativeSpeechLease() throws Exception {
        Conversion conversion = approvedGeneratingConversion("expired-workflow-lease");
        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE conversion_id = ? AND stage = 'SPEECH'
                """,
                conversion.conversionId());

        assertThatThrownBy(
                        () ->
                                generateSegment(
                                        conversion, manifest.segments().getFirst().operationKey()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow lease");
        verifyNoInteractions(openAiSpeechAdapter, googleSpeechAdapter);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM generation.speech_attempt WHERE conversion_id"
                                        + " = ?",
                                Integer.class,
                                conversion.conversionId()))
                .isZero();
    }

    @AfterAll
    static void stopFfmpeg() {
        FFMPEG.close();
    }

    private static FfmpegTestToolchain startFfmpeg() {
        try {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "folio-ffmpeg-itest");
            Files.createDirectories(directory);
            return FfmpegTestToolchain.start(directory);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {'R', 'I', 'F', 'F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(24_000);
        header.putInt(48_000);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put(new byte[] {'d', 'a', 't', 'a'});
        header.putInt(pcm.length);
        byte[] wav = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, wav, 0, 44);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    @Test
    void persistedManifestRowsCannotDriftAfterPreparation() throws Exception {
        Conversion conversion = approvedGeneratingConversion("immutable-manifest");
        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "UPDATE generation.speech_segment SET segment_ordinal = 9"
                                                + " WHERE operation_key = ?",
                                        manifest.segments().getFirst().operationKey()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "UPDATE generation.audiobook_chapter_plan SET display_title"
                                                + " = 'Drifted' WHERE conversion_id = ?",
                                        conversion.conversionId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void workersPollGenerationThenPublishOnlyAfterEverySegmentIsAccepted() throws Exception {
        Conversion conversion = approvedGeneratingConversion("worker-polling");
        jdbcTemplate.update(
                "UPDATE workflow.audiobook_conversion SET state = 'FAILED' WHERE conversion_id <> ?"
                        + " AND state IN ('GENERATING', 'FINALIZING')",
                conversion.conversionId());
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET state = 'READY', attempt_count = 0, lease_owner = NULL,
                    lease_message_id = NULL, lease_expires_at = NULL
                WHERE conversion_id = ? AND stage = 'SPEECH'
                """,
                conversion.conversionId());
        given(openAiSpeechAdapter.synthesize(any()))
                .willReturn(
                        speechResult(
                                "provider-worker",
                                "gpt-4o-mini-tts-2025-12-15",
                                "eu",
                                "cedar",
                                wav(sine(1_000, 330))));

        assertThat(workerService.generatePending()).isOne();
        assertThat(workerService.packagePending()).isOne();
        assertThatThrownBy(
                        () ->
                                generationService.finalizeAudiobook(
                                        conversion.listenerId(), conversion.conversionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authoritatively accepted");
        assertThat(workerService.packagePending()).isOne();
        generationService.finalizeAudiobook(conversion.listenerId(), conversion.conversionId());
        assertThat(workerService.generatePending()).isZero();
        assertThat(workerService.packagePending()).isZero();
        assertThat(audiobookCount(conversion.conversionId())).isOne();
        assertThat(
                        jdbcTemplate.queryForList(
                                """
                                SELECT stage, state FROM workflow.conversion_stage_run
                                WHERE conversion_id = ? AND stage IN ('SPEECH', 'ASSEMBLY', 'PACKAGING')
                                ORDER BY stage
                                """,
                                conversion.conversionId()))
                .extracting(row -> row.get("stage") + ":" + row.get("state"))
                .containsExactly("ASSEMBLY:SUCCEEDED", "PACKAGING:SUCCEEDED", "SPEECH:SUCCEEDED");
    }

    @Test
    void speechAndPackagingDatabaseRolesAreStageScoped() throws Exception {
        String databaseUrl;
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            databaseUrl = connection.getMetaData().getURL().split("\\?", 2)[0];
        }
        assertStagePrivileges(
                databaseUrl,
                "folio_speech_worker",
                "speech-integration-test-only",
                """
                SELECT
                  has_table_privilege(current_user, 'generation.speech_attempt', 'UPDATE'),
                  has_table_privilege(current_user, 'workflow.audiobook_conversion', 'UPDATE'),
                  has_table_privilege(current_user, 'generation.packaged_audiobook_result', 'INSERT'),
                  has_schema_privilege(current_user, 'library', 'USAGE'),
                  pg_has_role(current_user, 'cloudsqlsuperuser', 'member')
                """,
                List.of(true, false, false, false, false));
        assertStagePrivileges(
                databaseUrl,
                "folio_packaging_worker",
                "packaging-integration-test-only",
                """
                SELECT
                  has_table_privilege(current_user, 'generation.accepted_segment', 'SELECT'),
                  has_table_privilege(current_user, 'generation.packaged_audiobook_result', 'INSERT'),
                  has_table_privilege(current_user, 'generation.speech_attempt', 'UPDATE'),
                  has_schema_privilege(current_user, 'library', 'USAGE'),
                  pg_has_role(current_user, 'cloudsqlsuperuser', 'member')
                """,
                List.of(true, true, false, false, false));
    }

    private static void assertStagePrivileges(
            String databaseUrl, String user, String password, String query, List<Boolean> expected)
            throws Exception {
        try (var connection = DriverManager.getConnection(databaseUrl, user, password);
                var statement = connection.createStatement();
                var grants = statement.executeQuery(query)) {
            assertThat(grants.next()).isTrue();
            List<Boolean> actual = new java.util.ArrayList<>();
            for (int column = 1; column <= expected.size(); column++) {
                actual.add(grants.getBoolean(column));
            }
            assertThat(actual).containsExactlyElementsOf(expected);
        }
    }

    private Conversion approvedGeneratingConversion(String suffix) throws Exception {
        UUID listenerId =
                listenerIdentityService
                        .establish(
                                new ExternalIdentity(
                                        URI.create("https://accounts.google.com"),
                                        "generation-" + suffix + "-" + UUID.randomUUID(),
                                        SignInProvider.GOOGLE,
                                        null,
                                        "Generation Listener"))
                        .listenerId();
        UUID attestationId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID sourcePublicationId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                "INSERT INTO admission.rights_attestation VALUES (?, ?, 'rights-v1', 'notice-v1',"
                        + " ?)",
                attestationId,
                listenerId,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO admission.publication_submission (
                    submission_id, listener_id, attestation_id, entitlement_reservation_id,
                    planned_conversion_id, state, declared_media_type, declared_byte_length,
                    declared_sha256, upload_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ADMITTED', 'application/epub+zip', 8, ?, ?, ?, ?)
                """,
                submissionId,
                listenerId,
                attestationId,
                UUID.randomUUID(),
                conversionId,
                "a".repeat(64),
                now.plusMinutes(15),
                now,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO admission.source_publication (
                    source_publication_id, listener_id, submission_id, media_type, byte_length, created_at
                ) VALUES (?, ?, ?, 'application/epub+zip', 8, ?)
                """,
                sourcePublicationId,
                listenerId,
                submissionId,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO workflow.audiobook_conversion (
                    conversion_id, listener_id, source_publication_id, state, reason_code, created_at
                ) VALUES (?, ?, ?, 'AWAITING_REVIEW', 'NARRATION_REVIEW_AVAILABLE', ?)
                """,
                conversionId,
                listenerId,
                sourcePublicationId,
                now);

        PublicationNarrationPlanInterpreter.StructuralProvenance provenance =
                new PublicationNarrationPlanInterpreter.StructuralProvenance(
                        PublicationNarrationPlanInterpreter.ProvenanceSource.EPUB_XHTML,
                        0,
                        "chapter.xhtml",
                        "chapter",
                        true,
                        new PublicationNarrationPlanInterpreter.Confidence(1.0));
        PublicationNarrationPlanInterpreter.NarrationPlan plan =
                new PublicationNarrationPlanInterpreter.NarrationPlan(
                        List.of(
                                new PublicationNarrationPlanInterpreter.Chapter(
                                        0,
                                        "A private chapter",
                                        provenance,
                                        List.of(
                                                new PublicationNarrationPlanInterpreter.NormalProse(
                                                        0, "First private paragraph.", provenance),
                                                new PublicationNarrationPlanInterpreter.NormalProse(
                                                        1,
                                                        "Second private paragraph.",
                                                        provenance)),
                                        List.of())),
                        List.of());
        byte[] planBytes = OBJECT_MAPPER.writeValueAsBytes(plan);
        NarrationPlanAssetStore.StoredAsset planAsset =
                planAssetStore.write(conversionId, planBytes);
        jdbcTemplate.update(
                """
                INSERT INTO narration.narration_plan (
                    narration_plan_id, listener_id, conversion_id, schema_version,
                    working_asset_ref, asset_sha256, chapter_count, review_item_count, created_at
                ) VALUES (?, ?, ?, 'narration-plan-v1', ?, ?, 1, 0, ?)
                """,
                UUID.randomUUID(),
                listenerId,
                conversionId,
                planAsset.reference(),
                planAsset.sha256(),
                now);
        NarrationReviewService.ReviewResult review =
                narrationReviewService.submit(
                        new NarrationReviewService.ReviewCommand(
                                listenerId,
                                conversionId,
                                NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                                List.of(),
                                0,
                                "review-" + suffix + "-29"));
        NarrationSelectionService.ConfirmedRecipe recipe =
                narrationSelectionService.confirm(
                        new NarrationSelectionService.ConfirmCommand(
                                listenerId,
                                conversionId,
                                ROWAN_ID,
                                NarrationSelectionService.NarrationPace.NATURAL,
                                review.conversionVersion(),
                                "recipe-" + suffix + "-29"));
        conversionService.beginSpeechGeneration(listenerId, conversionId);
        UUID workflowMessageId = UUID.randomUUID();
        long version =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM workflow.audiobook_conversion WHERE conversion_id = ?",
                        Long.class,
                        conversionId);
        assertThat(
                        workflowService
                                .claimDelivery(
                                        new ConversionWorkflowService.WorkDelivery(
                                                workflowMessageId,
                                                conversionId,
                                                ConversionWorkflowService.Stage.SPEECH,
                                                1,
                                                version,
                                                "generation-itest",
                                                Duration.ofHours(1)))
                                .disposition())
                .isEqualTo(ConversionWorkflowService.DeliveryDisposition.CLAIMED);
        return new Conversion(listenerId, conversionId, recipe.recipeId(), workflowMessageId);
    }

    private AudiobookGenerationService.AcceptedSegment generateSegment(
            Conversion conversion, String operationKey) {
        return generationService.generateSegment(
                new AudiobookGenerationService.ProviderCallCommand(
                        conversion.listenerId(),
                        conversion.conversionId(),
                        operationKey,
                        conversion.workflowMessageId()));
    }

    private void acceptSpeechStage(
            Conversion conversion, AudiobookGenerationService.GenerationManifest manifest) {
        assertThat(
                        workflowService
                                .acceptResult(
                                        new ConversionWorkflowService.StageResult(
                                                conversion.workflowMessageId(),
                                                conversion.conversionId(),
                                                ConversionWorkflowService.Stage.SPEECH,
                                                "speech-stage:" + conversion.conversionId(),
                                                "generation/manifests/" + manifest.manifestId(),
                                                manifest.manifestDigest(),
                                                true))
                                .disposition())
                .isIn(
                        ConversionWorkflowService.ResultDisposition.ACCEPTED,
                        ConversionWorkflowService.ResultDisposition.REPLAYED);
    }

    private int audiobookCount(UUID conversionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM library.private_audiobook WHERE conversion_id = ?",
                Integer.class,
                conversionId);
    }

    private static ProviderSpeechAdapter.SpeechResult speechResult(
            String requestId, String model, String region, String voice, byte[] audio) {
        return new ProviderSpeechAdapter.SpeechResult(
                requestId,
                model,
                region,
                voice,
                audio,
                new ProviderUsage("INPUT_CHARACTER", 24, "AUDIO_BYTE", audio.length),
                voice.contains("Neural2")
                        ? ProviderSpeechAdapter.ModelEvidenceSource.QUALIFIED_VOICE_TIER
                        : ProviderSpeechAdapter.ModelEvidenceSource.REQUESTED_MODEL);
    }

    private static byte[] sine(int durationMs, double frequency) {
        int sampleRate = 24_000;
        int samples = sampleRate * durationMs / 1_000;
        byte[] pcm = new byte[samples * 2];
        for (int index = 0; index < samples; index++) {
            short sample = (short) (8_000 * Math.sin(2 * Math.PI * frequency * index / sampleRate));
            pcm[index * 2] = (byte) (sample & 0xff);
            pcm[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return pcm;
    }

    private record Conversion(
            UUID listenerId, UUID conversionId, UUID recipeId, UUID workflowMessageId) {}
}
