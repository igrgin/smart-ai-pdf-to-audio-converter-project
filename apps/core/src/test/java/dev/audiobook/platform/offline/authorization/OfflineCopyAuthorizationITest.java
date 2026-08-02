package dev.audiobook.platform.offline.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.generation.assets.AudiobookAssetStore;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.offline.authorization.service.OfflineAccessService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class OfflineCopyAuthorizationITest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final AudiobookAssetStore assetStore;
    private final OfflineAccessService offlineAccessService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    OfflineCopyAuthorizationITest(
            MockMvc mockMvc,
            JdbcTemplate jdbcTemplate,
            AudiobookAssetStore assetStore,
            OfflineAccessService offlineAccessService) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.assetStore = assetStore;
        this.offlineAccessService = offlineAccessService;
    }

    @Test
    void ownerReceivesVerifiableBoundAuthorizationAndDeterministicChunkManifest() throws Exception {
        PublishedAudiobook published =
                publish("offline-owner", "abcdefghij".getBytes(StandardCharsets.UTF_8));
        UUID installationId = UUID.randomUUID();

        var response =
                mockMvc.perform(
                                authorizationRequest(
                                        published, installationId, "offline-operation-1"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.authorization.algorithm").value("ES256"))
                        .andExpect(jsonPath("$.authorization.keyId").value("offline-v1"))
                        .andExpect(
                                jsonPath("$.authorization.claims.listenerId")
                                        .value(published.listener().listenerId().toString()))
                        .andExpect(
                                jsonPath("$.authorization.claims.installationId")
                                        .value(installationId.toString()))
                        .andExpect(
                                jsonPath("$.authorization.claims.audiobookId")
                                        .value(published.audiobookId().toString()))
                        .andExpect(
                                jsonPath("$.authorization.claims.assetVersionId")
                                        .value(published.assetVersionId().toString()))
                        .andExpect(
                                jsonPath("$.authorization.claims.authorizationGeneration").value(1))
                        .andExpect(
                                jsonPath("$.authorization.claims.purpose")
                                        .value("OFFLINE_PLAYBACK"))
                        .andExpect(jsonPath("$.manifest.totalBytes").value(10))
                        .andExpect(
                                jsonPath("$.manifest.parts[0].entityTag")
                                        .value("sha256:" + published.mediaDigest()))
                        .andExpect(jsonPath("$.manifest.parts[0].chunks.length()").value(3))
                        .andExpect(jsonPath("$.manifest.parts[0].chunks[0].start").value(0))
                        .andExpect(jsonPath("$.manifest.parts[0].chunks[0].end").value(3))
                        .andExpect(
                                jsonPath("$.manifest.parts[0].chunks[0].sha256")
                                        .value(sha256("abcd".getBytes(StandardCharsets.UTF_8))))
                        .andReturn()
                        .getResponse();

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        JsonNode claims = body.path("authorization").path("claims");
        assertThat(
                        Duration.between(
                                Instant.parse(claims.path("issuedAt").asText()),
                                Instant.parse(claims.path("expiresAt").asText())))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofDays(30));
        assertThat(signatureIsValid(body.path("authorization"))).isTrue();

        var replay =
                mockMvc.perform(
                                authorizationRequest(
                                        published, installationId, "offline-operation-1"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        assertThat(objectMapper.readTree(replay.getContentAsByteArray()).path("authorization"))
                .isEqualTo(body.path("authorization"));

        mockMvc.perform(authorizationRequest(published, UUID.randomUUID(), "offline-operation-1"))
                .andExpect(status().isConflict());
    }

    @Test
    void revocationAdvancesTheAuthorizationGeneration() throws Exception {
        PublishedAudiobook published =
                publish("offline-revocation", "abcdefghij".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(authorizationRequest(published, UUID.randomUUID(), "before-revocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization.claims.authorizationGeneration").value(1));

        offlineAccessService.revoke(published.listener().listenerId(), published.audiobookId());

        mockMvc.perform(authorizationRequest(published, UUID.randomUUID(), "after-revocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization.claims.authorizationGeneration").value(2));
    }

    @Test
    void connectedQuarantineAssetReplacementAndListenerBanAdvanceGenerations() throws Exception {
        PublishedAudiobook published =
                publish("offline-lifecycle", "abcdefghij".getBytes(StandardCharsets.UTF_8));
        UUID listenerId = published.listener().listenerId();

        mockMvc.perform(
                        authorizationRequest(
                                published, UUID.randomUUID(), "before-lifecycle-change"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization.claims.authorizationGeneration").value(1));

        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'RIGHTS_QUARANTINED' WHERE"
                        + " audiobook_id = ?",
                published.audiobookId());
        assertThat(generation(listenerId, published.audiobookId())).isEqualTo(2);

        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'AVAILABLE' WHERE audiobook_id"
                        + " = ?",
                published.audiobookId());
        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET current_asset_version_id = NULL WHERE"
                        + " audiobook_id = ?",
                published.audiobookId());
        assertThat(generation(listenerId, published.audiobookId())).isEqualTo(3);

        jdbcTemplate.update(
                "UPDATE listener_identity SET access_state = 'BANNED' WHERE listener_id = ?",
                listenerId);
        assertThat(generation(listenerId, published.audiobookId())).isEqualTo(4);
    }

    @Test
    void swappedListenerAndUnavailableAudiobookHaveTheSameNotFoundBoundary() throws Exception {
        PublishedAudiobook owner =
                publish("offline-denial", "abcdefghij".getBytes(StandardCharsets.UTF_8));
        PublishedAudiobook other =
                publish("offline-other", "klmnopqrst".getBytes(StandardCharsets.UTF_8));
        UUID installationId = UUID.randomUUID();

        var swapped =
                mockMvc.perform(
                                authorizationRequest(
                                        owner.audiobookId(),
                                        owner.assetVersionId(),
                                        other.listener(),
                                        installationId,
                                        "swapped"))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse();

        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'RIGHTS_QUARANTINED' WHERE"
                        + " audiobook_id = ?",
                owner.audiobookId());
        var quarantined =
                mockMvc.perform(authorizationRequest(owner, installationId, "quarantined"))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse();

        assertThat(quarantined.getContentAsString()).isEqualTo(swapped.getContentAsString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authorizationRequest(
                    PublishedAudiobook published, UUID installationId, String operationKey) {
        return authorizationRequest(
                published.audiobookId(),
                published.assetVersionId(),
                published.listener(),
                installationId,
                operationKey);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authorizationRequest(
                    UUID audiobookId,
                    UUID assetVersionId,
                    ListenerPrincipal listener,
                    UUID installationId,
                    String operationKey) {
        return post(
                        "/api/v1/audiobooks/{audiobookId}/asset-versions/{assetVersionId}/offline-copy-authorizations",
                        audiobookId,
                        assetVersionId)
                .contentType("application/json")
                .header("Accept", "application/json")
                .header("Origin", "http://localhost:3000")
                .header("Idempotency-Key", operationKey)
                .content("{\"installationId\":\"" + installationId + "\"}")
                .with(csrf())
                .with(
                        authentication(
                                UsernamePasswordAuthenticationToken.authenticated(
                                        listener, "session", Set.of())));
    }

    private boolean signatureIsValid(JsonNode authorization) throws Exception {
        byte[] publicKey = Base64.getDecoder().decode(authorization.path("publicKey").asText());
        byte[] payload = Base64.getUrlDecoder().decode(authorization.path("payload").asText());
        byte[] signatureBytes =
                Base64.getUrlDecoder().decode(authorization.path("signature").asText());
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(
                KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(payload);
        return verifier.verify(signatureBytes);
    }

    private long generation(UUID listenerId, UUID audiobookId) {
        return jdbcTemplate.queryForObject(
                "SELECT generation FROM offline_access.authorization_generation WHERE listener_id ="
                        + " ? AND audiobook_id = ?",
                Long.class,
                listenerId,
                audiobookId);
    }

    private PublishedAudiobook publish(String suffix, byte[] media) throws Exception {
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
        String mediaDigest = sha256(media);
        String manifestDigest = sha256(("manifest-" + suffix).getBytes(StandardCharsets.UTF_8));
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
                "INSERT INTO listener_identity (listener_id, display_name, contact_email,"
                        + " created_at) VALUES (?, ?, ?, ?)",
                listenerId,
                "Listener " + suffix,
                suffix + "@example.test",
                Timestamp.from(now));
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
                ) VALUES (?, ?, ?, ?, ?, 'ADMITTED', 'application/pdf', 10, ?, ?, ?, ?)
                """,
                submissionId,
                listenerId,
                attestationId,
                UUID.randomUUID(),
                conversionId,
                "a".repeat(64),
                Timestamp.from(now.plusSeconds(900)),
                Timestamp.from(now),
                Timestamp.from(now));
        jdbcTemplate.update(
                "INSERT INTO admission.source_publication (source_publication_id, listener_id,"
                        + " submission_id, media_type, byte_length, created_at) VALUES (?, ?, ?,"
                        + " 'application/pdf', 10, ?)",
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
                    'gpt-4o-mini-tts-2025-12-15', 'eu', 'eu-private-v1',
                    '30000000-0000-7000-8000-000000000001', 'rowan-openai-v1', 'cedar',
                    CAST('{"speed":1.0}' AS jsonb), 'folio-preview-v1', 'speech-eval-2026-08',
                    'segments-v1', 'audio-v1', 'toolchain-v1', ?, ?)
                """,
                recipeId,
                conversionId,
                listenerId,
                sha256(("recipe-" + suffix).getBytes(StandardCharsets.UTF_8)),
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
                sha256(("recipe-" + suffix).getBytes(StandardCharsets.UTF_8)),
                manifestKey,
                manifestDigest,
                media.length,
                Timestamp.from(now));
        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET current_asset_version_id = ? WHERE"
                        + " audiobook_id = ?",
                assetVersionId,
                audiobookId);
        jdbcTemplate.update(
                "INSERT INTO library.audiobook_chapter (chapter_id, asset_version_id, listener_id,"
                    + " chapter_ordinal, display_title, start_ms, duration_ms) VALUES (?, ?, ?, 0,"
                    + " 'Offline chapter', 0, 10000)",
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
                        null,
                        Set.of(SignInProvider.GOOGLE),
                        SignInProvider.GOOGLE,
                        Instant.now()),
                audiobookId,
                assetVersionId,
                mediaDigest);
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record PublishedAudiobook(
            ListenerPrincipal listener,
            UUID audiobookId,
            UUID assetVersionId,
            String mediaDigest) {}
}
