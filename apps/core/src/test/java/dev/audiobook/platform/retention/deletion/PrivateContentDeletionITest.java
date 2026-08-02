package dev.audiobook.platform.retention.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.generation.assets.AudiobookAssetStore;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.offline.authorization.service.OfflineAccessService;
import dev.audiobook.platform.retention.erasure.service.ErasureWorkerService;
import dev.audiobook.platform.retention.deletion.service.DeletionRequestService;
import dev.audiobook.platform.retention.restore.service.TombstoneReplayService;
import dev.audiobook.platform.retention.reconciliation.service.ErasureReconciliationService;
import dev.audiobook.platform.trustoperations.casework.TrustOperationsCaseProjector;
import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class PrivateContentDeletionITest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final AudiobookAssetStore assetStore;
    private final OfflineAccessService offlineAccessService;
    private final ErasureWorkerService erasureWorkerService;
    private final DeletionRequestService deletionRequestService;
    private final TombstoneReplayService tombstoneReplayService;
    private final ErasureReconciliationService erasureReconciliationService;
    private final TrustOperationsCaseProjector trustOperationsCaseProjector;
    private final TombstoneRegistry tombstoneRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    PrivateContentDeletionITest(
            MockMvc mockMvc,
            JdbcTemplate jdbcTemplate,
            AudiobookAssetStore assetStore,
            OfflineAccessService offlineAccessService,
            ErasureWorkerService erasureWorkerService,
            DeletionRequestService deletionRequestService,
            TombstoneReplayService tombstoneReplayService,
            ErasureReconciliationService erasureReconciliationService,
            TrustOperationsCaseProjector trustOperationsCaseProjector,
            TombstoneRegistry tombstoneRegistry) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.assetStore = assetStore;
        this.offlineAccessService = offlineAccessService;
        this.erasureWorkerService = erasureWorkerService;
        this.deletionRequestService = deletionRequestService;
        this.tombstoneReplayService = tombstoneReplayService;
        this.erasureReconciliationService = erasureReconciliationService;
        this.trustOperationsCaseProjector = trustOperationsCaseProjector;
        this.tombstoneRegistry = tombstoneRegistry;
    }

    @Test
    void audiobookDeletionIsAcceptedAfterImmediateDenialAndGenerationAdvance() throws Exception {
        PublishedAudiobook published = publish("delete-audiobook");
        offlineAccessService.issue(
                new OfflineAccessService.IssueAuthorization(
                        published.listener().listenerId(),
                        UUID.randomUUID(),
                        published.audiobookId(),
                        published.assetVersionId(),
                        "authorization-before-deletion"));

        var response =
                mockMvc.perform(
                                delete("/api/v1/audiobooks/{audiobookId}", published.audiobookId())
                                        .header("Origin", "http://localhost:3000")
                                        .header("Idempotency-Key", "delete-audiobook-operation")
                                        .header("If-Match", "\"0\"")
                                        .with(csrf())
                                        .with(authentication(session(published.listener()))))
                        .andExpect(status().isAccepted())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.scope").value("AUDIOBOOK"))
                        .andExpect(jsonPath("$.state").value("ACCEPTED"))
                        .andExpect(jsonPath("$.liveErasureDueAt").exists())
                        .andExpect(jsonPath("$.providerEvidenceDueAt").exists())
                        .andExpect(jsonPath("$.backupExpiresAt").exists())
                        .andReturn()
                        .getResponse();

        UUID requestId =
                UUID.fromString(
                        objectMapper
                                .readTree(response.getContentAsByteArray())
                                .path("requestId")
                                .asText());
        assertThat(response.getHeader("Location"))
                .isEqualTo("/api/v1/deletions/" + requestId);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT availability FROM library.private_audiobook WHERE audiobook_id = ?",
                                String.class,
                                published.audiobookId()))
                .isEqualTo("DELETING");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT generation FROM offline_access.authorization_generation"
                                        + " WHERE listener_id = ? AND audiobook_id = ?",
                                Long.class,
                                published.listener().listenerId(),
                                published.audiobookId()))
                .isEqualTo(2L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM retention.deletion_tombstone WHERE request_id = ?",
                                Integer.class,
                                requestId))
                .isOne();
        assertThat(tombstoneRegistry.entries())
                .anyMatch(tombstone -> tombstone.requestId().equals(requestId));
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM retention.erasure_obligation WHERE request_id = ?",
                                Integer.class,
                                requestId))
                .isGreaterThanOrEqualTo(3);

        mockMvc.perform(
                        get(
                                        "/api/v1/audiobooks/{audiobookId}/asset-versions/{assetVersionId}/manifest",
                                        published.audiobookId(),
                                        published.assetVersionId())
                                .with(authentication(session(published.listener()))))
                .andExpect(status().isNotFound());

        assertThat(erasureWorkerService.erasePending()).isEqualTo(4);
        assertThat(erasureWorkerService.erasePending()).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM retention.deletion_request WHERE request_id = ?",
                                String.class,
                                requestId))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM library.private_audiobook WHERE audiobook_id = ?",
                                Integer.class,
                                published.audiobookId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM retention.erasure_evidence WHERE request_id = ?",
                                Integer.class,
                                requestId))
                .isOne();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM provider.operation_evidence"
                                        + " WHERE generation_recipe_id = ?",
                                Integer.class,
                                published.recipeId()))
                .isZero();
        assertThatThrownBy(() -> assetStore.readFinal(published.manifestObjectKey()))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> assetStore.readFinal(published.partObjectKey()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void accountDeletionDeniesIdentitySessionsAndEveryOfflineAuthorization() throws Exception {
        PublishedAudiobook published = publish("delete-account");
        offlineAccessService.issue(
                new OfflineAccessService.IssueAuthorization(
                        published.listener().listenerId(),
                        UUID.randomUUID(),
                        published.audiobookId(),
                        published.assetVersionId(),
                        "authorization-before-account-deletion"));
        jdbcTemplate.update(
                """
                INSERT INTO spring_session (
                    primary_id, session_id, creation_time, last_access_time,
                    max_inactive_interval, expiry_time, principal_name
                ) VALUES (?, ?, 1, 1, 1800, 1800001, ?)
                """,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                published.listener().listenerId().toString());

        var response =
                mockMvc.perform(
                                delete("/api/v1/account")
                                        .header("Origin", "http://localhost:3000")
                                        .header("Idempotency-Key", "delete-account-operation")
                                        .with(csrf())
                                        .with(authentication(session(published.listener()))))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.scope").value("ACCOUNT"))
                        .andExpect(jsonPath("$.state").value("ACCEPTED"))
                        .andReturn()
                        .getResponse();
        UUID requestId =
                UUID.fromString(
                        objectMapper
                                .readTree(response.getContentAsByteArray())
                                .path("requestId")
                                .asText());

        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT access_state, display_name, contact_email"
                                        + " FROM listener_identity WHERE listener_id = ?",
                                published.listener().listenerId()))
                .containsEntry("access_state", "DELETED")
                .containsEntry("display_name", "Deleted Listener")
                .containsEntry("contact_email", null);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM external_identity_link WHERE listener_id = ?",
                                Integer.class,
                                published.listener().listenerId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM spring_session WHERE principal_name = ?",
                                Integer.class,
                                published.listener().listenerId().toString()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT generation FROM offline_access.authorization_generation"
                                        + " WHERE listener_id = ? AND audiobook_id = ?",
                                Long.class,
                                published.listener().listenerId(),
                                published.audiobookId()))
                .isEqualTo(2L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM retention.external_identity_tombstone"
                                        + " WHERE request_id = ?",
                                Integer.class,
                                requestId))
                .isOne();

        assertThat(erasureWorkerService.erasePending()).isEqualTo(4);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT state FROM retention.deletion_request WHERE request_id = ?",
                                String.class,
                                requestId))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM library.private_audiobook"
                                        + " WHERE listener_id = ?",
                                Integer.class,
                                published.listener().listenerId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM workflow.audiobook_conversion"
                                        + " WHERE listener_id = ?",
                                Integer.class,
                                published.listener().listenerId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM narration.generation_recipe"
                                        + " WHERE listener_id = ?",
                                Integer.class,
                                published.listener().listenerId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM admission.publication_submission"
                                        + " WHERE listener_id = ?",
                                Integer.class,
                                published.listener().listenerId()))
                .isZero();
    }

    @Test
    void restoreReplayDeniesResurrectionAndReissuesErasureObligations() throws Exception {
        PublishedAudiobook published = publish("restore-replay");
        var deletion =
                deletionRequestService.deleteAudiobook(
                        new DeletionRequest.DeleteAudiobookCommand(
                                published.listener().listenerId(),
                                published.audiobookId(),
                                0,
                                "restore-replay-delete"));
        jdbcTemplate.update(
                "UPDATE retention.erasure_obligation SET state = 'COMPLETED', locator = NULL, completed_at = ?"
                        + " WHERE request_id = ?",
                Timestamp.from(Instant.now()),
                deletion.requestId());
        jdbcTemplate.update(
                "UPDATE retention.deletion_request SET state = 'COMPLETED', completed_at = ?"
                        + " WHERE request_id = ?",
                Timestamp.from(Instant.now()),
                deletion.requestId());
        jdbcTemplate.update(
                "UPDATE listener_identity SET access_state = 'BANNED' WHERE listener_id = ?",
                published.listener().listenerId());
        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'AVAILABLE'"
                        + " WHERE audiobook_id = ?",
                published.audiobookId());

        var replay = tombstoneReplayService.replay();

        assertThat(replay.referencesDenied()).isOne();
        assertThat(replay.requestsReissued()).isOne();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT availability FROM library.private_audiobook"
                                        + " WHERE audiobook_id = ?",
                                String.class,
                                published.audiobookId()))
                .isEqualTo("DELETING");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT generation FROM offline_access.authorization_generation"
                                        + " WHERE listener_id = ? AND audiobook_id = ?",
                                Long.class,
                                published.listener().listenerId(),
                                published.audiobookId()))
                .isEqualTo(4L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM retention.deletion_request"
                                        + " WHERE resource_digest = (SELECT resource_digest"
                                        + " FROM retention.deletion_request WHERE request_id = ?)",
                                Integer.class,
                                deletion.requestId()))
                .isEqualTo(2);
        assertThat(erasureWorkerService.erasePending()).isEqualTo(4);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM library.private_audiobook"
                                        + " WHERE audiobook_id = ?",
                                Integer.class,
                                published.audiobookId()))
                .isZero();
    }

    @Test
    void missedErasureTargetCreatesAnUrgentSecurityReviewCase() throws Exception {
        PublishedAudiobook published = publish("missed-target");
        var deletion =
                deletionRequestService.deleteAudiobook(
                        new DeletionRequest.DeleteAudiobookCommand(
                                published.listener().listenerId(),
                                published.audiobookId(),
                                0,
                                "missed-target-delete"));
        jdbcTemplate.update(
                "UPDATE retention.deletion_request SET quick_erasure_due_at = ?"
                        + " WHERE request_id = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)),
                deletion.requestId());

        assertThat(erasureReconciliationService.reconcile()).isOne();
        trustOperationsCaseProjector.projectAuthoritativeCases();

        assertThat(
                        jdbcTemplate.queryForMap(
                                """
                                SELECT case_type, required_role, safety_priority, urgency
                                FROM trust_operations.operations_case
                                WHERE correlation_id LIKE 'compliance-incident:%'
                                  AND resolved_at IS NULL
                                ORDER BY deadline DESC
                                LIMIT 1
                                """))
                .containsEntry("case_type", "COMPLIANCE_INCIDENT")
                .containsEntry("required_role", "SECURITY_REVIEWER")
                .containsEntry("safety_priority", 100)
                .containsEntry("urgency", 100);
        assertThat(erasureWorkerService.erasePending()).isEqualTo(4);
        assertThat(erasureReconciliationService.reconcile()).isZero();
    }

    @Test
    void erasureCompletionCannotCrossTheActiveRequestBoundary() throws Exception {
        PublishedAudiobook active = publish("active-erasure-boundary");
        PublishedAudiobook foreign = publish("foreign-erasure-boundary");
        var activeDeletion =
                deletionRequestService.deleteAudiobook(
                        new DeletionRequest.DeleteAudiobookCommand(
                                active.listener().listenerId(),
                                active.audiobookId(),
                                0,
                                "active-erasure-boundary"));
        var foreignDeletion =
                deletionRequestService.deleteAudiobook(
                        new DeletionRequest.DeleteAudiobookCommand(
                                foreign.listener().listenerId(),
                                foreign.audiobookId(),
                                0,
                                "foreign-erasure-boundary"));
        UUID foreignObligation =
                jdbcTemplate.queryForObject(
                        "SELECT obligation_id FROM retention.erasure_obligation"
                                + " WHERE request_id = ? AND category = 'RELATIONAL'",
                        UUID.class,
                        foreignDeletion.requestId());
        jdbcTemplate.update(
                "UPDATE retention.deletion_request SET state = 'ERASING'"
                        + " WHERE request_id IN (?, ?)",
                activeDeletion.requestId(),
                foreignDeletion.requestId());
        jdbcTemplate.update(
                "UPDATE retention.erasure_obligation SET state = 'ERASING'"
                        + " WHERE obligation_id = ?",
                foreignObligation);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.queryForObject(
                                        """
                                        WITH configured AS MATERIALIZED (
                                            SELECT set_config('app.erasure_request_id', ?, true)
                                        )
                                        SELECT retention.complete_erasure_obligation(
                                            ?::uuid, 'PRIVATE_RELATIONAL_DATA_DELETED'::varchar
                                        )
                                        FROM configured
                                        """,
                                        Object.class,
                                        activeDeletion.requestId().toString(),
                                        foreignObligation))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("not authorized");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT locator IS NOT NULL FROM retention.erasure_obligation"
                                        + " WHERE obligation_id = ?",
                                Boolean.class,
                                foreignObligation))
                .isTrue();

        jdbcTemplate.update(
                "UPDATE retention.erasure_obligation SET state = 'PENDING'"
                        + " WHERE obligation_id = ?",
                foreignObligation);
        assertThat(erasureWorkerService.erasePending()).isEqualTo(8);
    }

    private PublishedAudiobook publish(String suffix) throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID attestationId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        UUID recipeId = UUID.randomUUID();
        UUID audiobookId = UUID.randomUUID();
        UUID assetVersionId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID partId = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] media = "private audio".getBytes(StandardCharsets.UTF_8);
        String mediaDigest = sha256(media);
        String manifestDigest = sha256(("manifest-" + suffix).getBytes(StandardCharsets.UTF_8));
        String recipeDigest = sha256(("recipe-" + suffix).getBytes(StandardCharsets.UTF_8));
        String objectKey =
                "audiobooks/"
                        + audiobookId
                        + "/assets/"
                        + assetVersionId
                        + "/chapters/0/parts/0-"
                        + mediaDigest
                        + ".mp3";
        String manifestKey =
                "audiobooks/"
                        + audiobookId
                        + "/assets/"
                        + assetVersionId
                        + "/manifest-"
                        + manifestDigest
                        + ".json";

        jdbcTemplate.update(
                "INSERT INTO listener_identity (listener_id, display_name, contact_email, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                listenerId,
                "Listener " + suffix,
                suffix + "@example.test",
                Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO external_identity_link (issuer, subject, listener_id, provider)"
                        + " VALUES ('https://login.eu.example', ?, ?, 'GOOGLE')",
                "subject-" + suffix,
                listenerId);
        jdbcTemplate.update(
                "INSERT INTO admission.rights_attestation (attestation_id, listener_id,"
                        + " terms_version, notice_version, submitted_at) VALUES (?, ?, 'rights-v1',"
                        + " 'notice-v1', ?)",
                attestationId,
                listenerId,
                Timestamp.from(now));
        jdbcTemplate.update(
                """
                INSERT INTO admission.publication_submission (
                    submission_id, listener_id, attestation_id, entitlement_reservation_id,
                    planned_conversion_id, state, declared_media_type, declared_byte_length,
                    declared_sha256, upload_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ADMITTED', 'application/pdf', 13, ?, ?, ?, ?)
                """,
                submissionId,
                listenerId,
                attestationId,
                UUID.randomUUID(),
                conversionId,
                sha256("source".getBytes(StandardCharsets.UTF_8)),
                Timestamp.from(now.plusSeconds(900)),
                Timestamp.from(now),
                Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO admission.source_publication (source_publication_id, listener_id,"
                        + " submission_id, media_type, byte_length, created_at) VALUES (?, ?, ?,"
                        + " 'application/pdf', 13, ?)",
                publicationId,
                listenerId,
                submissionId,
                Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO workflow.audiobook_conversion (conversion_id, listener_id,"
                        + " source_publication_id, state, reason_code, created_at) VALUES (?, ?, ?,"
                        + " 'FINALIZED', 'PRIVATE_AUDIOBOOK_AVAILABLE', ?)",
                conversionId,
                listenerId,
                publicationId,
                Timestamp.from(now));
        jdbcTemplate.update(
                """
                INSERT INTO narration.generation_recipe (
                    recipe_id, conversion_id, listener_id, narrator_voice_id, voice_display_name,
                    pace, capability_profile_id, capability_profile_version, provider, service,
                    endpoint, model_snapshot, region, data_policy_version, voice_mapping_id,
                    mapping_version, provider_voice, native_controls, preview_version,
                    evaluation_version, segmentation_policy_version, audio_policy_version,
                    toolchain_version, recipe_digest, created_at
                ) VALUES (?, ?, ?, '10000000-0000-7000-8000-000000000001', 'Rowan',
                    'NATURAL', '20000000-0000-7000-8000-000000000001', 'openai-speech-eu-v1',
                    'openai', 'speech', 'https://eu.api.openai.com/v1/audio/speech',
                    'gpt-4o-mini-tts-2025-12-15', 'eu', 'openai-eu-zdr-v1',
                    '30000000-0000-7000-8000-000000000001', 'rowan-openai-v1', 'cedar',
                    CAST('{"speed":1.0}' AS jsonb), 'folio-preview-v1', 'speech-eval-2026-08',
                    'segments-v1', 'audio-v1', 'toolchain-v1', ?, ?)
                """,
                recipeId,
                conversionId,
                listenerId,
                recipeDigest,
                Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO library.private_audiobook (audiobook_id, listener_id, conversion_id,"
                        + " availability, created_at) VALUES (?, ?, ?, 'AVAILABLE', ?)",
                audiobookId,
                listenerId,
                conversionId,
                Timestamp.from(now));
        jdbcTemplate.update(
                """
                INSERT INTO provider.operation_evidence (
                    operation_id, service, capability_profile_id, capability_profile_version,
                    generation_recipe_id, provider_request_id, actual_model,
                    model_evidence_source, actual_region, input_meter, input_units,
                    output_meter, output_units, price_meter, outcome_sha256, recorded_at
                ) VALUES (?, 'SPEECH', '20000000-0000-7000-8000-000000000001',
                    'openai-speech-eu-v1', ?, ?, 'gpt-4o-mini-tts-2025-12-15',
                    'PROVIDER_RESPONSE', 'eu', 'characters', 13, 'audio_bytes', 13,
                    'characters', ?, ?)
                """,
                "speech-operation-" + suffix,
                recipeId,
                "provider-request-" + suffix,
                mediaDigest,
                Timestamp.from(now));
        jdbcTemplate.update(
                """
                INSERT INTO library.audiobook_asset_version (
                    asset_version_id, audiobook_id, listener_id, generation_recipe_id,
                    recipe_digest, manifest_object_key, manifest_digest, packaging_profile_version,
                    total_duration_ms, total_bytes, integrated_loudness_lufs, true_peak_dbtp,
                    applied_gain_db, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'mp3-v1', 10000, ?, -18, -2, 0, ?)
                """,
                assetVersionId,
                audiobookId,
                listenerId,
                recipeId,
                recipeDigest,
                manifestKey,
                manifestDigest,
                media.length,
                Timestamp.from(now));
        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET current_asset_version_id = ?"
                        + " WHERE audiobook_id = ?",
                assetVersionId,
                audiobookId);
        jdbcTemplate.update(
                "INSERT INTO library.audiobook_chapter (chapter_id, asset_version_id, listener_id,"
                        + " chapter_ordinal, display_title, start_ms, duration_ms) VALUES (?, ?, ?,"
                        + " 0, 'Private chapter', 0, 10000)",
                chapterId,
                assetVersionId,
                listenerId);
        jdbcTemplate.update(
                """
                INSERT INTO library.final_asset_part (
                    part_id, chapter_id, asset_version_id, listener_id, chapter_ordinal,
                    part_ordinal, object_key, mime_type, byte_length, duration_ms, sha256
                ) VALUES (?, ?, ?, ?, 0, 0, ?, 'audio/mpeg', ?, 10000, ?)
                """,
                partId,
                chapterId,
                assetVersionId,
                listenerId,
                objectKey,
                media.length,
                mediaDigest);
        assetStore.writeFinal(objectKey, media, "audio/mpeg");

        return new PublishedAudiobook(
                new ListenerPrincipal(
                        listenerId,
                        "Listener " + suffix,
                        suffix + "@example.test",
                        Set.of(SignInProvider.GOOGLE),
                        SignInProvider.GOOGLE,
                        now),
                audiobookId,
                assetVersionId,
                conversionId,
                recipeId,
                manifestKey,
                objectKey);
    }

    private static UsernamePasswordAuthenticationToken session(ListenerPrincipal listener) {
        return UsernamePasswordAuthenticationToken.authenticated(listener, "session", Set.of());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record PublishedAudiobook(
            ListenerPrincipal listener,
            UUID audiobookId,
            UUID assetVersionId,
            UUID conversionId,
            UUID recipeId,
            String manifestObjectKey,
            String partObjectKey) {}
}
