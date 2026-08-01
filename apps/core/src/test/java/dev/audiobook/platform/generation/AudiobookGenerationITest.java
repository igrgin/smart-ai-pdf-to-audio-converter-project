package dev.audiobook.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.ExternalIdentity;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationReviewService;
import dev.audiobook.platform.narration.NarrationSelectionService;
import dev.audiobook.platform.narration.PublicationNarrationPlanInterpreter;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
class AudiobookGenerationITest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final UUID ROWAN_ID = UUID.fromString("10000000-0000-7000-8000-000000000001");

    private final AudiobookGenerationService generationService;
    private final ListenerIdentityService listenerIdentityService;
    private final NarrationReviewService narrationReviewService;
    private final NarrationSelectionService narrationSelectionService;
    private final AudiobookConversionService conversionService;
    private final NarrationPlanAssetStore planAssetStore;
    private final AudiobookAssetStore audiobookAssetStore;
    private final AudiobookGenerationWorkerService workerService;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SpeechProvider speechProvider;

    @MockitoBean
    private CanonicalSpeechDecoder speechDecoder;

    @MockitoBean
    private AudioPackagingService packagingService;

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
            JdbcTemplate jdbcTemplate) {
        this.generationService = generationService;
        this.listenerIdentityService = listenerIdentityService;
        this.narrationReviewService = narrationReviewService;
        this.narrationSelectionService = narrationSelectionService;
        this.conversionService = conversionService;
        this.planAssetStore = planAssetStore;
        this.audiobookAssetStore = audiobookAssetStore;
        this.workerService = workerService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void approvedPlanFinalizesExactlyOnceDespiteReverseCompletionAndRedelivery() throws Exception {
        Conversion conversion = approvedGeneratingConversion("complete");
        given(speechProvider.synthesize(any())).willAnswer(invocation -> {
            SpeechProvider.SpeechRequest request = invocation.getArgument(0);
            return new SpeechProvider.SpeechResult(
                    "provider-" + request.spokenText().hashCode(),
                    request.model(),
                    request.region(),
                    request.voice(),
                    sine(3_000, 220 + Math.abs(request.spokenText().hashCode() % 200)));
        });
        given(speechDecoder.decode(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(packagingService.packageAudiobook(any())).willReturn(packagedAudiobook());

        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());
        List<AudiobookGenerationService.Segment> reverse = manifest.segments().reversed();
        for (AudiobookGenerationService.Segment segment : reverse) {
            generationService.generateSegment(
                    conversion.listenerId(), conversion.conversionId(), segment.operationKey());
        }
        AudiobookGenerationService.AcceptedSegment replay = generationService.generateSegment(
                conversion.listenerId(), conversion.conversionId(), reverse.getFirst().operationKey());

        AudiobookGenerationService.PrivateAudiobook finalized =
                generationService.finalizeAudiobook(conversion.listenerId(), conversion.conversionId());
        AudiobookGenerationService.PrivateAudiobook finalizationReplay =
                generationService.finalizeAudiobook(conversion.listenerId(), conversion.conversionId());

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
        assertThat(conversionService.conversion(conversion.listenerId(), conversion.conversionId()).state())
                .isEqualTo(AudiobookConversionService.ConversionState.FINALIZED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM generation.speech_attempt WHERE conversion_id = ?",
                        Integer.class,
                        conversion.conversionId()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM generation.accepted_segment WHERE conversion_id = ?",
                        Integer.class,
                        conversion.conversionId()))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM library.private_audiobook WHERE conversion_id = ? AND availability = 'AVAILABLE'",
                        Integer.class,
                        conversion.conversionId()))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM generation.working_asset_erasure_obligation WHERE conversion_id = ?",
                        Integer.class,
                        conversion.conversionId()))
                .isOne();
        String manifestKey = jdbcTemplate.queryForObject(
                "SELECT manifest_object_key FROM library.audiobook_asset_version WHERE asset_version_id = ?",
                String.class,
                finalized.assetVersionId());
        assertThat(audiobookAssetStore.readFinal(manifestKey)).isNotEmpty();
        verify(speechProvider, times(2)).synthesize(any());
    }

    @Test
    void missingOrInvalidSpeechNeverExposesAPartialPrivateAudiobook() throws Exception {
        Conversion missing = approvedGeneratingConversion("missing");
        generationService.prepare(missing.listenerId(), missing.conversionId());

        assertThatThrownBy(() -> generationService.finalizeAudiobook(
                        missing.listenerId(), missing.conversionId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not complete");
        assertThat(audiobookCount(missing.conversionId())).isZero();

        Conversion drift = approvedGeneratingConversion("drift");
        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(drift.listenerId(), drift.conversionId());
        given(speechProvider.synthesize(any())).willAnswer(invocation -> {
            SpeechProvider.SpeechRequest request = invocation.getArgument(0);
            return new SpeechProvider.SpeechResult(
                    "provider-drift", "unapproved-model", request.region(), request.voice(), sine(3_000, 220));
        });
        given(speechDecoder.decode(any())).willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> generationService.generateSegment(
                        drift.listenerId(), drift.conversionId(), manifest.segments().getFirst().operationKey()))
                .isInstanceOf(SpeechValidationException.class);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM generation.speech_attempt WHERE conversion_id = ?",
                        String.class,
                        drift.conversionId()))
                .isEqualTo("FAILED");
        assertThat(audiobookCount(drift.conversionId())).isZero();
    }

    @Test
    void persistedManifestRowsCannotDriftAfterPreparation() throws Exception {
        Conversion conversion = approvedGeneratingConversion("immutable-manifest");
        AudiobookGenerationService.GenerationManifest manifest =
                generationService.prepare(conversion.listenerId(), conversion.conversionId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE generation.speech_segment SET segment_ordinal = 9 WHERE operation_key = ?",
                        manifest.segments().getFirst().operationKey()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE generation.audiobook_chapter_plan SET display_title = 'Drifted' WHERE conversion_id = ?",
                        conversion.conversionId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void workersPollGenerationThenPublishOnlyAfterEverySegmentIsAccepted() throws Exception {
        Conversion conversion = approvedGeneratingConversion("worker-polling");
        jdbcTemplate.update(
                "UPDATE workflow.audiobook_conversion SET state = 'FAILED' WHERE conversion_id <> ? AND state IN ('GENERATING', 'FINALIZING')",
                conversion.conversionId());
        given(speechProvider.synthesize(any())).willAnswer(invocation -> {
            SpeechProvider.SpeechRequest request = invocation.getArgument(0);
            return new SpeechProvider.SpeechResult(
                    "provider-worker",
                    request.model(),
                    request.region(),
                    request.voice(),
                    sine(1_000, 330));
        });
        given(speechDecoder.decode(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(packagingService.packageAudiobook(any())).willReturn(packagedAudiobook());

        assertThat(workerService.generatePending()).isOne();
        assertThat(workerService.packageAndFinalizePending()).isOne();
        assertThat(workerService.generatePending()).isZero();
        assertThat(workerService.packageAndFinalizePending()).isZero();
        assertThat(audiobookCount(conversion.conversionId())).isOne();
    }

    private Conversion approvedGeneratingConversion(String suffix) throws Exception {
        UUID listenerId = listenerIdentityService.establish(new ExternalIdentity(
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
                "INSERT INTO admission.rights_attestation VALUES (?, ?, 'rights-v1', 'notice-v1', ?)",
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
                        List.of(new PublicationNarrationPlanInterpreter.Chapter(
                                0,
                                "A private chapter",
                                provenance,
                                List.of(
                                        new PublicationNarrationPlanInterpreter.NormalProse(
                                                0, "First private paragraph.", provenance),
                                        new PublicationNarrationPlanInterpreter.NormalProse(
                                                1, "Second private paragraph.", provenance)),
                                List.of())),
                        List.of());
        byte[] planBytes = OBJECT_MAPPER.writeValueAsBytes(plan);
        NarrationPlanAssetStore.StoredAsset planAsset = planAssetStore.write(conversionId, planBytes);
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
        NarrationReviewService.ReviewResult review = narrationReviewService.submit(
                new NarrationReviewService.ReviewCommand(
                        listenerId,
                        conversionId,
                        NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                        List.of(),
                        0,
                        "review-" + suffix + "-29"));
        NarrationSelectionService.ConfirmedRecipe recipe = narrationSelectionService.confirm(
                new NarrationSelectionService.ConfirmCommand(
                        listenerId,
                        conversionId,
                        ROWAN_ID,
                        NarrationSelectionService.NarrationPace.NATURAL,
                        review.conversionVersion(),
                        "recipe-" + suffix + "-29"));
        conversionService.beginSpeechGeneration(listenerId, conversionId);
        return new Conversion(listenerId, conversionId, recipe.recipeId());
    }

    private int audiobookCount(UUID conversionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM library.private_audiobook WHERE conversion_id = ?",
                Integer.class,
                conversionId);
    }

    private static AudioPackagingService.PackagingResult packagedAudiobook() {
        byte[] mp3 = new byte[] {73, 68, 51, 29};
        String digest = SpeechSegmentationServiceImpl.sha256Bytes(mp3);
        return new AudioPackagingService.PackagingResult(
                "mono-24k-mp3-v1",
                List.of(new AudioPackagingService.PackagedChapter(
                        0,
                        "A private chapter",
                        0,
                        6_000,
                        List.of(new AudioPackagingService.PackagedPart(
                                0, "audio/mpeg", mp3, mp3.length, 6_000, digest)))),
                6_000,
                mp3.length,
                -18.0,
                -1.5,
                0.0,
                "b".repeat(64));
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

    private record Conversion(UUID listenerId, UUID conversionId, UUID recipeId) {
    }
}
