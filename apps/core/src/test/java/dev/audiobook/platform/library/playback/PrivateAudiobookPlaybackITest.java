package dev.audiobook.platform.library.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.generation.assets.AudiobookAssetStore;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.library.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class PrivateAudiobookPlaybackITest {

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbcTemplate;
    private final AudiobookAssetStore assetStore;

    @Autowired
    PrivateAudiobookPlaybackITest(
            MockMvc mockMvc, JdbcTemplate jdbcTemplate, AudiobookAssetStore assetStore) {
        this.mockMvc = mockMvc;
        this.jdbcTemplate = jdbcTemplate;
        this.assetStore = assetStore;
    }

    @Test
    void ownerReadsChapterManifestAndImmutableMediaWithHttpRangeSemantics() throws Exception {
        PublishedAudiobook published =
                publish(
                        "owner-range",
                        "First light",
                        "abcdefghij".getBytes(StandardCharsets.UTF_8));
        String manifestPath = manifestPath(published);
        String mediaPath =
                "/api/v1/audiobooks/"
                        + published.audiobookId()
                        + "/asset-versions/"
                        + published.assetVersionId()
                        + "/parts/"
                        + published.partId()
                        + "/media";

        mockMvc.perform(
                        get(manifestPath)
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        header().string("ETag", '"' + "sha256:" + published.manifestDigest() + '"'))
                .andExpect(jsonPath("$.audiobookId").value(published.audiobookId().toString()))
                .andExpect(
                        jsonPath("$.assetVersionId").value(published.assetVersionId().toString()))
                .andExpect(jsonPath("$.chapters[0].title").value("First light"))
                .andExpect(jsonPath("$.chapters[0].parts[0].mediaUrl").value(mediaPath))
                .andExpect(jsonPath("$.resume.positionMs").value(0))
                .andExpect(jsonPath("$.resume.version").value(0));

        mockMvc.perform(
                        head(mediaPath)
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(new byte[0]));

        mockMvc.perform(
                        get(mediaPath)
                                .header("Range", "bytes=2-5")
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(header().string("Content-Length", "4"))
                .andExpect(content().bytes("cdef".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(
                        get(mediaPath)
                                .header("Range", "bytes=2-5")
                                .header("If-Range", '"' + "stale" + '"')
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Range"))
                .andExpect(content().bytes("abcdefghij".getBytes(StandardCharsets.UTF_8)));

        String objectKey =
                jdbcTemplate.queryForObject(
                        "SELECT object_key FROM library.final_asset_part WHERE part_id = ?",
                        String.class,
                        published.partId());
        Path storedAsset =
                Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "folio-final-tests/audiobooks",
                        objectKey);
        Files.write(storedAsset, "corrupted!".getBytes(StandardCharsets.UTF_8));
        assertThat(denied(head(mediaPath), published.listener()).status()).isEqualTo(404);
        assertThat(
                        denied(get(mediaPath).header("Range", "bytes=2-5"), published.listener())
                                .status())
                .isEqualTo(404);
    }

    @Test
    void resumePositionUpdatesAreIdempotentAndRejectStaleVersions() throws Exception {
        PublishedAudiobook published =
                publish(
                        "resume",
                        "A remembered place",
                        "abcdefghij".getBytes(StandardCharsets.UTF_8));
        String positionPath =
                "/api/v1/audiobooks/"
                        + published.audiobookId()
                        + "/asset-versions/"
                        + published.assetVersionId()
                        + "/playback-position";
        var update =
                put(positionPath)
                        .contentType("application/json")
                        .content("{\"positionMs\":4200}")
                        .header("Origin", "http://localhost:3000")
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "resume-operation")
                        .with(csrf())
                        .with(authentication(sessionAuthentication(published.listener())));

        mockMvc.perform(update)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.positionMs").value(4200))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(update)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));

        mockMvc.perform(
                        put(positionPath)
                                .contentType("application/json")
                                .content("{\"positionMs\":5000}")
                                .header("Origin", "http://localhost:3000")
                                .header("If-Match", "\"0\"")
                                .header("Idempotency-Key", "stale-operation")
                                .with(csrf())
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(
                        put(positionPath)
                                .contentType("application/json")
                                .content("{\"positionMs\":5000}")
                                .header("Origin", "http://localhost:3000")
                                .header("If-Match", "\"1\"")
                                .header("Idempotency-Key", "resume-operation")
                                .with(csrf())
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isConflict());

        mockMvc.perform(
                        put(positionPath)
                                .contentType("application/json")
                                .content("{\"positionMs\":10001}")
                                .header("Origin", "http://localhost:3000")
                                .header("If-Match", "\"1\"")
                                .header("Idempotency-Key", "position-beyond-duration")
                                .with(csrf())
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        put(positionPath)
                                .contentType("application/json")
                                .content("{\"positionMs\":5000}")
                                .header("Origin", "http://localhost:3000")
                                .header("If-Match", "1")
                                .header("Idempotency-Key", "malformed-version")
                                .with(csrf())
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(
                        put(positionPath)
                                .contentType("application/json")
                                .content("{\"positionMs\":5000}")
                                .header("Origin", "http://localhost:3000")
                                .header("If-Match", "\"1\"")
                                .header("Idempotency-Key", "x".repeat(201))
                                .with(csrf())
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        get(manifestPath(published))
                                .with(authentication(sessionAuthentication(published.listener()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resume.positionMs").value(4200))
                .andExpect(jsonPath("$.resume.version").value(1));
    }

    @Test
    void swappedOwnershipAndUnavailableStatesDenyWithoutExistenceLeakage() throws Exception {
        PublishedAudiobook other =
                publish(
                        "other-denial",
                        "Other chapter",
                        "otherbytes".getBytes(StandardCharsets.UTF_8));
        String swappedObjectKey =
                "audiobooks/"
                        + other.audiobookId()
                        + "/assets/"
                        + other.assetVersionId()
                        + "/chapters/0/parts/99-"
                        + "0".repeat(64)
                        + ".mp3";
        PublishedAudiobook swappedOwner =
                publish(
                        "owner-denial",
                        "Owner chapter",
                        "ownerbytes".getBytes(StandardCharsets.UTF_8),
                        swappedObjectKey);
        PublishedAudiobook owner =
                publish(
                        "state-denial",
                        "State chapter",
                        "statebytes".getBytes(StandardCharsets.UTF_8));
        PublishedAudiobook actionOwner =
                publish(
                        "action-denial",
                        "Action chapter",
                        "actionbytes".getBytes(StandardCharsets.UTF_8));
        String ownerManifest = manifestPath(owner);
        String swappedMedia =
                "/api/v1/audiobooks/"
                        + owner.audiobookId()
                        + "/asset-versions/"
                        + other.assetVersionId()
                        + "/parts/"
                        + other.partId()
                        + "/media";
        String swappedManifest =
                "/api/v1/audiobooks/"
                        + owner.audiobookId()
                        + "/asset-versions/"
                        + other.assetVersionId()
                        + "/manifest";
        String unknownManifest =
                "/api/v1/audiobooks/"
                        + UUID.randomUUID()
                        + "/asset-versions/"
                        + UUID.randomUUID()
                        + "/manifest";

        MvcDenial crossListener = denied(get(ownerManifest), other.listener());
        MvcDenial unknown = denied(get(unknownManifest), other.listener());
        MvcDenial swapped = denied(get(swappedMedia), owner.listener());
        MvcDenial swappedManifestDenial = denied(get(swappedManifest), owner.listener());
        assertThat(crossListener)
                .isEqualTo(unknown)
                .isEqualTo(swapped)
                .isEqualTo(swappedManifestDenial);

        assertThat(denied(get(mediaPath(swappedOwner)), swappedOwner.listener()))
                .isEqualTo(unknown);
        assertThat(denied(get(manifestPath(swappedOwner)), swappedOwner.listener()))
                .isEqualTo(unknown);

        jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion SET state = 'FAILED'
                WHERE conversion_id = (
                    SELECT conversion_id FROM library.private_audiobook WHERE audiobook_id = ?
                )
                """,
                actionOwner.audiobookId());
        assertThat(denied(get(manifestPath(actionOwner)), actionOwner.listener()))
                .isEqualTo(unknown);
        assertThat(denied(get(mediaPath(actionOwner)), actionOwner.listener())).isEqualTo(unknown);

        for (String availability :
                List.of("RIGHTS_QUARANTINED", "TECHNICALLY_UNAVAILABLE", "DELETING", "ERASED")) {
            jdbcTemplate.update(
                    "UPDATE library.private_audiobook SET availability = ? WHERE audiobook_id = ?",
                    availability,
                    owner.audiobookId());
            assertThat(denied(get(ownerManifest), owner.listener())).isEqualTo(unknown);
        }

        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'AVAILABLE' WHERE audiobook_id"
                        + " = ?",
                owner.audiobookId());
        jdbcTemplate.update(
                "UPDATE listener_identity SET access_state = 'BANNED' WHERE listener_id = ?",
                owner.listener().listenerId());
        assertThat(denied(get(ownerManifest), owner.listener())).isEqualTo(unknown);
    }

    @Test
    void invalidOrMultipleRangesReturnTheSameUnsatisfiedRangeBoundary() throws Exception {
        PublishedAudiobook published =
                publish(
                        "range-denial",
                        "Range chapter",
                        "abcdefghij".getBytes(StandardCharsets.UTF_8));
        String mediaPath =
                "/api/v1/audiobooks/"
                        + published.audiobookId()
                        + "/asset-versions/"
                        + published.assetVersionId()
                        + "/parts/"
                        + published.partId()
                        + "/media";

        for (String range : List.of("bytes=20-30", "bytes=0-1,4-5", "items=0-1")) {
            mockMvc.perform(
                            get(mediaPath)
                                    .header("Range", range)
                                    .with(
                                            authentication(
                                                    sessionAuthentication(published.listener()))))
                    .andExpect(status().isRequestedRangeNotSatisfiable())
                    .andExpect(header().string("Content-Range", "bytes */10"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    private PublishedAudiobook publish(String suffix, String chapterTitle, byte[] media)
            throws Exception {
        return publish(suffix, chapterTitle, media, null);
    }

    private PublishedAudiobook publish(
            String suffix, String chapterTitle, byte[] media, String storedObjectKey)
            throws Exception {
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
                    + " ?, 0, 10000)",
                chapterId,
                assetVersionId,
                listenerId,
                chapterTitle);
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
                storedObjectKey == null ? objectKey : storedObjectKey,
                media.length,
                mediaDigest);
        assetStore.writeFinal(objectKey, media, "audio/mpeg");

        return new PublishedAudiobook(
                principal(listenerId, "Listener " + suffix),
                audiobookId,
                assetVersionId,
                partId,
                manifestDigest);
    }

    private static ListenerPrincipal principal(UUID listenerId, String displayName) {
        return new ListenerPrincipal(
                listenerId,
                displayName,
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
    }

    private static String manifestPath(PublishedAudiobook published) {
        return "/api/v1/audiobooks/"
                + published.audiobookId()
                + "/asset-versions/"
                + published.assetVersionId()
                + "/manifest";
    }

    private static String mediaPath(PublishedAudiobook published) {
        return "/api/v1/audiobooks/"
                + published.audiobookId()
                + "/asset-versions/"
                + published.assetVersionId()
                + "/parts/"
                + published.partId()
                + "/media";
    }

    private static UsernamePasswordAuthenticationToken sessionAuthentication(
            ListenerPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, "session", Set.of());
    }

    private MvcDenial denied(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            ListenerPrincipal principal)
            throws Exception {
        var response =
                mockMvc.perform(request.with(authentication(sessionAuthentication(principal))))
                        .andExpect(status().isNotFound())
                        .andReturn()
                        .getResponse();
        return new MvcDenial(response.getStatus(), response.getContentAsString());
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record PublishedAudiobook(
            ListenerPrincipal listener,
            UUID audiobookId,
            UUID assetVersionId,
            UUID partId,
            String manifestDigest) {}

    private record MvcDenial(int status, String body) {}
}
