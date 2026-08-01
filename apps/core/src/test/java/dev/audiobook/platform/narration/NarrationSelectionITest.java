package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.ExternalIdentity;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@Transactional
class NarrationSelectionITest {

    private static final UUID ROWAN_ID = UUID.fromString("10000000-0000-7000-8000-000000000001");
    private static final UUID MARLOWE_ID = UUID.fromString("10000000-0000-7000-8000-000000000002");
    private static final UUID PROFILE_ID = UUID.fromString("20000000-0000-7000-8000-000000000001");
    private static final UUID ROWAN_MAPPING_ID = UUID.fromString("30000000-0000-7000-8000-000000000001");

    private final NarrationSelectionService narrationSelectionService;
    private final ListenerIdentityService listenerIdentityService;
    private final AudiobookConversionService audiobookConversionService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    NarrationSelectionITest(
            NarrationSelectionService narrationSelectionService,
            ListenerIdentityService listenerIdentityService,
            AudiobookConversionService audiobookConversionService,
            JdbcTemplate jdbcTemplate) {
        this.narrationSelectionService = narrationSelectionService;
        this.listenerIdentityService = listenerIdentityService;
        this.audiobookConversionService = audiobookConversionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void catalogKeepsSixStableProviderNeutralVoicesAndNaturalAsTheInitialPace() {
        NarrationSelectionService.VoiceCatalog catalog = narrationSelectionService.catalog();

        assertThat(catalog.voices()).hasSize(6);
        assertThat(catalog.voices())
                .extracting(NarrationSelectionService.NarratorVoice::displayName)
                .containsExactly("Rowan", "Marlowe", "Ellis", "Clara", "Ansel", "Sloane");
        assertThat(catalog.voices())
                .extracting(voice -> voice.preview().passageVersion())
                .containsOnly("folio-preview-v1");
        assertThat(catalog.voices())
                .extracting(voice -> voice.preview().uri())
                .doesNotHaveDuplicates();
        assertThat(catalog.voices())
                .allSatisfy(voice -> {
                    assertThat(voice.descriptors()).hasSize(2);
                    assertThat(voice.descriptorReviewVersion()).isEqualTo("voice-review-2026-07");
                    assertThat(voice.preview().durationSeconds()).isBetween(25, 35);
                });
        assertThat(catalog.paces()).containsExactly(
                NarrationSelectionService.NarrationPace.MEASURED,
                NarrationSelectionService.NarrationPace.NATURAL,
                NarrationSelectionService.NarrationPace.BRISK);
        assertThat(catalog.defaultPace()).isEqualTo(NarrationSelectionService.NarrationPace.NATURAL);
    }

    @Test
    void confirmationFreezesEveryEligibleGenerationInputAndReplaysExactly() {
        Conversion conversion = conversion("freeze");
        var command = new NarrationSelectionService.ConfirmCommand(
                conversion.listenerId(),
                conversion.conversionId(),
                ROWAN_ID,
                NarrationSelectionService.NarrationPace.NATURAL,
                0,
                "freeze-recipe-28");

        NarrationSelectionService.ConfirmedRecipe confirmed = narrationSelectionService.confirm(command);
        NarrationSelectionService.ConfirmedRecipe replay = narrationSelectionService.confirm(command);
        NarrationSelectionService.GenerationAuthorization authorization = audiobookConversionService.beginSpeechGeneration(
                conversion.listenerId(), conversion.conversionId());

        assertThat(replay).isEqualTo(confirmed);
        assertThat(confirmed.voiceDisplayName()).isEqualTo("Rowan");
        assertThat(confirmed.pace()).isEqualTo(NarrationSelectionService.NarrationPace.NATURAL);
        assertThat(confirmed.recipeDigest()).matches("[0-9a-f]{64}");
        assertThat(confirmed.conversionVersion()).isEqualTo(1);
        assertThat(authorization)
                .extracting(
                        NarrationSelectionService.GenerationAuthorization::recipeId,
                        NarrationSelectionService.GenerationAuthorization::recipeDigest)
                .containsExactly(confirmed.recipeId(), confirmed.recipeDigest());
        assertThat(audiobookConversionService.conversions(conversion.listenerId()))
                .extracting(AudiobookConversionService.AudiobookConversion::state)
                .containsExactly(AudiobookConversionService.ConversionState.GENERATING);

        Map<String, Object> frozen = jdbcTemplate.queryForMap(
                """
                SELECT capability_profile_id, capability_profile_version, provider, service, endpoint,
                       model_snapshot, region, data_policy_version, voice_mapping_id, mapping_version,
                       provider_voice, native_controls::text AS native_controls, preview_version,
                       evaluation_version, segmentation_policy_version, audio_policy_version,
                       toolchain_version
                FROM narration.generation_recipe WHERE recipe_id = ?
                """,
                confirmed.recipeId());
        assertThat(frozen)
                .containsEntry("capability_profile_id", PROFILE_ID)
                .containsEntry("capability_profile_version", "openai-speech-eu-v1")
                .containsEntry("provider", "openai")
                .containsEntry("service", "speech")
                .containsEntry("endpoint", "https://eu.api.openai.com/v1/audio/speech")
                .containsEntry("model_snapshot", "gpt-4o-mini-tts-2025-12-15")
                .containsEntry("region", "eu")
                .containsEntry("data_policy_version", "eu-private-v1")
                .containsEntry("voice_mapping_id", ROWAN_MAPPING_ID)
                .containsEntry("mapping_version", "rowan-openai-v1")
                .containsEntry("preview_version", "folio-preview-v1")
                .containsEntry("evaluation_version", "speech-eval-2026-08")
                .containsEntry("segmentation_policy_version", "semantic-segments-v1")
                .containsEntry("audio_policy_version", "mono-24k-mp3-v1")
                .containsEntry("toolchain_version", "speech-worker-ffmpeg-v1");
        assertThat(frozen.get("native_controls").toString())
                .contains("\"speed\": 1.0")
                .contains("Voice: Warm and grounded audiobook narration.")
                .contains("Delivery: Natural pace, attentive phrasing, and calm confidence.");
    }

    @Test
    void unavailableAndRetiredVoicesCannotBeSelectedForNewRecipes() {
        Conversion unissued = conversion("unissued");
        assertThatThrownBy(() -> narrationSelectionService.confirm(new NarrationSelectionService.ConfirmCommand(
                        unissued.listenerId(),
                        unissued.conversionId(),
                        UUID.randomUUID(),
                        NarrationSelectionService.NarrationPace.NATURAL,
                        0,
                        "unissued-voice-28")))
                .isInstanceOf(NarrationSelectionRejectedException.class)
                .extracting(exception -> ((NarrationSelectionRejectedException) exception).reason())
                .isEqualTo(NarrationRejectionReason.UNISSUED_VOICE_IDENTIFIER);

        Conversion temporary = conversion("temporary");
        jdbcTemplate.update(
                "UPDATE narration.narrator_voice SET availability = 'TEMPORARILY_UNAVAILABLE' WHERE voice_id = ?",
                ROWAN_ID);

        assertRejected(
                temporary,
                "temporarily-unavailable-28",
                NarrationRejectionReason.VOICE_TEMPORARILY_UNAVAILABLE);

        jdbcTemplate.update("UPDATE narration.narrator_voice SET availability = 'RETIRED' WHERE voice_id = ?", ROWAN_ID);
        Conversion retired = conversion("retired");
        assertRejected(retired, "retired-28", NarrationRejectionReason.VOICE_RETIRED);
    }

    @Test
    void confirmationAndGenerationBothFailClosedWhenEligibilityBecomesStale() {
        Conversion staleAtConfirmation = conversion("stale-confirmation");
        jdbcTemplate.update(
                "UPDATE narration.provider_capability_profile SET expires_at = ? WHERE profile_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                PROFILE_ID);

        assertRejected(
                staleAtConfirmation,
                "stale-confirmation-28",
                NarrationRejectionReason.CAPABILITY_PROFILE_STALE);

        jdbcTemplate.update(
                "UPDATE narration.provider_capability_profile SET expires_at = ? WHERE profile_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).plusYears(1),
                PROFILE_ID);
        Conversion staleAtGeneration = conversion("stale-generation");
        NarrationSelectionService.ConfirmedRecipe confirmed = narrationSelectionService.confirm(command(
                staleAtGeneration, "stale-generation-28"));
        jdbcTemplate.update(
                "UPDATE narration.voice_mapping SET mapping_state = 'STALE' WHERE mapping_id = ?",
                ROWAN_MAPPING_ID);

        assertThatThrownBy(() -> audiobookConversionService.beginSpeechGeneration(
                        staleAtGeneration.listenerId(), staleAtGeneration.conversionId()))
                .isInstanceOf(NarrationSelectionRejectedException.class)
                .extracting(exception -> ((NarrationSelectionRejectedException) exception).reason())
                .isEqualTo(NarrationRejectionReason.EXPLICIT_NEW_CHOICE_REQUIRED);
        assertThat(audiobookConversionService.conversions(staleAtGeneration.listenerId()))
                .extracting(AudiobookConversionService.AudiobookConversion::state)
                .containsExactly(AudiobookConversionService.ConversionState.PREPARING);
        assertThat(narrationSelectionService.narrationChoice(
                        staleAtGeneration.listenerId(), staleAtGeneration.conversionId()))
                .extracting(
                        NarrationSelectionService.NarrationChoiceStatus::conversionVersion,
                        NarrationSelectionService.NarrationChoiceStatus::explicitChoiceRequired)
                .containsExactly(1L, true);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM narration.generation_recipe WHERE recipe_id = ?",
                Long.class,
                confirmed.recipeId())).isEqualTo(1L);

        NarrationSelectionService.ConfirmedRecipe replacement = narrationSelectionService.confirm(
                new NarrationSelectionService.ConfirmCommand(
                        staleAtGeneration.listenerId(),
                        staleAtGeneration.conversionId(),
                        MARLOWE_ID,
                        NarrationSelectionService.NarrationPace.NATURAL,
                        1,
                        "explicit-replacement-28"));

        assertThat(replacement.conversionVersion()).isEqualTo(2);
        assertThat(replacement.recipeId()).isNotEqualTo(confirmed.recipeId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT supersedes_recipe_id FROM narration.generation_recipe WHERE recipe_id = ?",
                UUID.class,
                replacement.recipeId())).isEqualTo(confirmed.recipeId());
        assertThat(narrationSelectionService.narrationChoice(
                        staleAtGeneration.listenerId(), staleAtGeneration.conversionId()).explicitChoiceRequired())
                .isFalse();
    }

    @Test
    void regionAndDataPolicyMismatchFailsClosedInsteadOfChoosingAnotherMapping() {
        Conversion conversion = conversion("policy-mismatch");
        jdbcTemplate.update(
                "UPDATE narration.voice_mapping SET required_region = 'us' WHERE mapping_id = ?",
                ROWAN_MAPPING_ID);

        assertRejected(
                conversion,
                "policy-mismatch-28",
                NarrationRejectionReason.UNSUPPORTED_REGION_OR_DATA_POLICY);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM narration.generation_recipe WHERE conversion_id = ?",
                Long.class,
                conversion.conversionId())).isZero();
    }

    private void assertRejected(
            Conversion conversion, String operationKey, NarrationRejectionReason reason) {
        assertThatThrownBy(() -> narrationSelectionService.confirm(command(conversion, operationKey)))
                .isInstanceOf(NarrationSelectionRejectedException.class)
                .extracting(exception -> ((NarrationSelectionRejectedException) exception).reason())
                .isEqualTo(reason);
    }

    private static NarrationSelectionService.ConfirmCommand command(Conversion conversion, String operationKey) {
        return new NarrationSelectionService.ConfirmCommand(
                conversion.listenerId(),
                conversion.conversionId(),
                ROWAN_ID,
                NarrationSelectionService.NarrationPace.NATURAL,
                0,
                operationKey);
    }

    private Conversion conversion(String suffix) {
        UUID listenerId = listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "narration-" + suffix + "-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Narration Listener"))
                .listenerId();
        UUID attestationId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID sourcePublicationId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO admission.rights_attestation (
                    attestation_id, listener_id, terms_version, notice_version, submitted_at
                ) VALUES (?, ?, 'rights-v1', 'notice-v1', ?)
                """,
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
                    conversion_id, listener_id, source_publication_id, state, created_at
                ) VALUES (?, ?, ?, 'PREPARING', ?)
                """,
                conversionId,
                listenerId,
                sourcePublicationId,
                now);
        return new Conversion(listenerId, conversionId);
    }

    private record Conversion(UUID listenerId, UUID conversionId) {
    }
}
