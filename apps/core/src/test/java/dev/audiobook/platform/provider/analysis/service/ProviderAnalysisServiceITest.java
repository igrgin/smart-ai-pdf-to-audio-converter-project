package dev.audiobook.platform.provider.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.listener.service.ListenerIdentityService;
import dev.audiobook.platform.identity.signin.ExternalIdentity;
import dev.audiobook.platform.narration.selection.service.NarrationSelectionService;
import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.analysis.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
class ProviderAnalysisServiceITest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ProviderAnalysisService analysisService;
    private final JdbcTemplate jdbcTemplate;
    private final ListenerIdentityService listenerIdentityService;
    private final NarrationSelectionService narrationSelectionService;

    @MockitoBean(name = "openAiProviderAnalysisAdapterImpl")
    private ProviderAnalysisAdapter analysisAdapter;

    @Autowired
    ProviderAnalysisServiceITest(
            ProviderAnalysisService analysisService,
            JdbcTemplate jdbcTemplate,
            ListenerIdentityService listenerIdentityService,
            NarrationSelectionService narrationSelectionService) {
        this.analysisService = analysisService;
        this.jdbcTemplate = jdbcTemplate;
        this.listenerIdentityService = listenerIdentityService;
        this.narrationSelectionService = narrationSelectionService;
    }

    @Test
    void boundedCanonicalTextReturnsSchemaValidatedOutcomeWithExactEvidence() throws Exception {
        UUID recipeId = generationRecipe("valid");
        String operationId = UUID.randomUUID().toString();
        given(analysisAdapter.provider()).willReturn("openai");
        given(analysisAdapter.analyze(any()))
                .willReturn(
                        new ProviderAnalysisAdapter.AnalysisResult(
                                "provider-request-opaque",
                                "gpt-5-mini-2025-08-07",
                                "eu",
                                OBJECT_MAPPER.readTree(
                                        """
                                        {"classification":"NORMAL_PROSE","confidence":0.97}
                                        """),
                                new ProviderUsage("INPUT_TOKEN", 12, "OUTPUT_TOKEN", 7)));

        ProviderAnalysisService.AnalysisOutcome outcome =
                analysisService.analyze(
                        new ProviderAnalysisService.AnalysisCommand(
                                operationId,
                                recipeId,
                                "openai-analysis-eu-v1",
                                new ProviderAnalysisService.CanonicalTextUnit(
                                        "Only the necessary paragraph."),
                                new ProviderAnalysisService.OutputSchema(
                                        "narration-classification-v1",
                                        Map.of(
                                                "classification",
                                                        ProviderAnalysisService.ValueType.STRING,
                                                "confidence",
                                                        ProviderAnalysisService.ValueType.NUMBER),
                                        Set.of("classification", "confidence"),
                                        false)));

        assertThat(outcome.result().path("classification").asText()).isEqualTo("NORMAL_PROSE");
        assertThat(outcome.evidence().capabilityProfileVersion())
                .isEqualTo("openai-analysis-eu-v1");
        assertThat(outcome.evidence().usage())
                .isEqualTo(new ProviderUsage("INPUT_TOKEN", 12, "OUTPUT_TOKEN", 7));
        assertThat(
                        jdbcTemplate.queryForMap(
                                """
                                SELECT capability_profile_version, generation_recipe_id,
                                       model_evidence_source,
                                       input_units, output_units
                                FROM provider.operation_evidence WHERE operation_id = ?
                                """,
                                operationId))
                .containsEntry("capability_profile_version", "openai-analysis-eu-v1")
                .containsEntry("generation_recipe_id", recipeId)
                .containsEntry("model_evidence_source", "PROVIDER_RESPONSE")
                .containsEntry("input_units", 12L)
                .containsEntry("output_units", 7L);
    }

    @Test
    void rejectsAnOutcomeThatDoesNotMatchTheRequestedSchema() throws Exception {
        UUID recipeId = generationRecipe("invalid-schema");
        given(analysisAdapter.provider()).willReturn("openai");
        given(analysisAdapter.analyze(any()))
                .willReturn(
                        new ProviderAnalysisAdapter.AnalysisResult(
                                "provider-request-invalid",
                                "gpt-5-mini-2025-08-07",
                                "eu",
                                OBJECT_MAPPER.readTree("{\"classification\":7}"),
                                new ProviderUsage("INPUT_TOKEN", 2, "OUTPUT_TOKEN", 1)));

        assertThatThrownBy(
                        () ->
                                analysisService.analyze(
                                        new ProviderAnalysisService.AnalysisCommand(
                                                UUID.randomUUID().toString(),
                                                recipeId,
                                                "openai-analysis-eu-v1",
                                                new ProviderAnalysisService.CanonicalTextUnit(
                                                        "Necessary text."),
                                                new ProviderAnalysisService.OutputSchema(
                                                        "classification-v1",
                                                        Map.of(
                                                                "classification",
                                                                ProviderAnalysisService.ValueType
                                                                        .STRING),
                                                        Set.of("classification"),
                                                        false))))
                .isInstanceOf(ProviderAnalysisException.class)
                .extracting(exception -> ((ProviderAnalysisException) exception).code())
                .isEqualTo(ProviderAnalysisException.Code.INVALID_SCHEMA_OUTCOME);
    }

    @Test
    void rejectsCanonicalInputBeyondTheQualifiedProfileLimit() {
        UUID recipeId = generationRecipe("too-large");
        given(analysisAdapter.provider()).willReturn("openai");

        assertThatThrownBy(
                        () ->
                                analysisService.analyze(
                                        new ProviderAnalysisService.AnalysisCommand(
                                                UUID.randomUUID().toString(),
                                                recipeId,
                                                "openai-analysis-eu-v1",
                                                new ProviderAnalysisService.CanonicalTextUnit(
                                                        "x".repeat(250_001)),
                                                new ProviderAnalysisService.OutputSchema(
                                                        "classification-v1",
                                                        Map.of(
                                                                "classification",
                                                                ProviderAnalysisService.ValueType
                                                                        .STRING),
                                                        Set.of("classification"),
                                                        false))))
                .isInstanceOf(ProviderAnalysisException.class)
                .extracting(exception -> ((ProviderAnalysisException) exception).code())
                .isEqualTo(ProviderAnalysisException.Code.INPUT_LIMIT_EXCEEDED);
    }

    private UUID generationRecipe(String suffix) {
        UUID listenerId =
                listenerIdentityService
                        .establish(
                                new ExternalIdentity(
                                        URI.create("https://accounts.google.com"),
                                        "provider-analysis-" + suffix + "-" + UUID.randomUUID(),
                                        SignInProvider.GOOGLE,
                                        null,
                                        "Provider Analysis Listener"))
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
                    conversion_id, listener_id, source_publication_id, state, reason_code, created_at
                ) VALUES (?, ?, ?, 'AWAITING_REVIEW', 'NARRATION_REVIEW_AVAILABLE', ?)
                """,
                conversionId,
                listenerId,
                sourcePublicationId,
                now);
        return narrationSelectionService
                .confirm(
                        new NarrationSelectionService.ConfirmCommand(
                                listenerId,
                                conversionId,
                                UUID.fromString("10000000-0000-7000-8000-000000000001"),
                                NarrationSelectionService.NarrationPace.NATURAL,
                                0,
                                "analysis-recipe-" + suffix + "-" + UUID.randomUUID()))
                .recipeId();
    }
}
