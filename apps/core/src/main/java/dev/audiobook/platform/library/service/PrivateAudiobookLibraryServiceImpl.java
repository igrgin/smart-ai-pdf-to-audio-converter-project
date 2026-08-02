package dev.audiobook.platform.library.service;

import dev.audiobook.platform.library.*;
import dev.audiobook.platform.library.FinalAudiobookAssetReader;
import dev.audiobook.platform.library.playback.HttpByteRange;
import dev.audiobook.platform.library.playback.PrivateAudiobookUnavailableException;
import dev.audiobook.platform.library.position.PlaybackPositionConflictException;
import dev.audiobook.platform.library.position.PlaybackPositionRejectedException;
import dev.audiobook.platform.library.position.PlaybackPositionRequestConflictException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PrivateAudiobookLibraryServiceImpl implements PrivateAudiobookLibraryService {

    private final JdbcTemplate jdbcTemplate;
    private final FinalAudiobookAssetReader assetStore;
    private final Clock identityClock;

    @Override
    public PrivateAudiobook find(UUID listenerId, UUID conversionId) {
        List<PrivateAudiobook> matches =
                jdbcTemplate.query(
                        """
                        SELECT pa.audiobook_id, pa.current_asset_version_id, pa.availability,
                               av.manifest_digest, av.total_duration_ms, pa.version
                        FROM library.private_audiobook pa
                        JOIN library.audiobook_asset_version av
                          ON av.asset_version_id = pa.current_asset_version_id
                        WHERE pa.listener_id = ? AND pa.conversion_id = ?
                        """,
                        (resultSet, row) ->
                                new PrivateAudiobook(
                                        resultSet.getObject("audiobook_id", UUID.class),
                                        resultSet.getObject("current_asset_version_id", UUID.class),
                                        resultSet.getString("availability"),
                                        resultSet.getString("manifest_digest"),
                                        resultSet.getLong("total_duration_ms"),
                                        resultSet.getLong("version")),
                        listenerId,
                        conversionId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    @Override
    @Transactional
    public PlaybackManifest manifest(UUID listenerId, UUID audiobookId, UUID assetVersionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(audiobookId, "audiobookId");
        Objects.requireNonNull(assetVersionId, "assetVersionId");
        List<ManifestHeader> matches =
                jdbcTemplate.query(
                        """
                        SELECT pa.audiobook_id, pa.conversion_id, av.asset_version_id,
                               av.manifest_object_key, av.manifest_digest, av.total_duration_ms, sp.media_type,
                               recipe.voice_display_name,
                               COALESCE(position.position_ms, 0) AS position_ms,
                               COALESCE(position.version, 0) AS position_version
                        FROM library.private_audiobook pa
                        JOIN library.audiobook_asset_version av
                          ON av.asset_version_id = pa.current_asset_version_id
                         AND av.audiobook_id = pa.audiobook_id
                         AND av.listener_id = pa.listener_id
                        JOIN workflow.audiobook_conversion conversion
                          ON conversion.conversion_id = pa.conversion_id
                         AND conversion.listener_id = pa.listener_id
                        JOIN admission.source_publication sp
                          ON sp.source_publication_id = conversion.source_publication_id
                         AND sp.listener_id = pa.listener_id
                        JOIN narration.generation_recipe recipe
                          ON recipe.recipe_id = av.generation_recipe_id
                         AND recipe.listener_id = pa.listener_id
                        JOIN listener_identity listener ON listener.listener_id = pa.listener_id
                        LEFT JOIN library.playback_position position
                          ON position.listener_id = pa.listener_id
                         AND position.audiobook_id = pa.audiobook_id
                         AND position.asset_version_id = av.asset_version_id
                        WHERE pa.listener_id = ?
                          AND pa.audiobook_id = ?
                          AND av.asset_version_id = ?
                          AND conversion.state = 'FINALIZED'
                          AND pa.availability = 'AVAILABLE'
                          AND listener.access_state = 'ACTIVE'
                        FOR SHARE OF pa, av, listener
                        """,
                        (resultSet, row) ->
                                new ManifestHeader(
                                        resultSet.getObject("audiobook_id", UUID.class),
                                        resultSet.getObject("asset_version_id", UUID.class),
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getString("media_type"),
                                        resultSet.getString("voice_display_name"),
                                        resultSet.getString("manifest_object_key"),
                                        resultSet.getString("manifest_digest"),
                                        resultSet.getLong("total_duration_ms"),
                                        resultSet.getLong("position_ms"),
                                        resultSet.getLong("position_version")),
                        listenerId,
                        audiobookId,
                        assetVersionId);
        if (matches.isEmpty()) {
            throw new PrivateAudiobookUnavailableException();
        }
        ManifestHeader header = matches.getFirst();
        String expectedManifestKey =
                "audiobooks/"
                        + header.audiobookId()
                        + "/assets/"
                        + header.assetVersionId()
                        + "/manifest-"
                        + header.manifestDigest()
                        + ".json";
        if (!expectedManifestKey.equals(header.manifestObjectKey())) {
            throw new PrivateAudiobookUnavailableException();
        }
        List<PlaybackChapter> chapters =
                jdbcTemplate.query(
                        """
                        SELECT chapter_id, chapter_ordinal, display_title, start_ms, duration_ms
                        FROM library.audiobook_chapter
                        WHERE listener_id = ? AND asset_version_id = ?
                        ORDER BY chapter_ordinal
                        """,
                        (resultSet, row) -> {
                            UUID chapterId = resultSet.getObject("chapter_id", UUID.class);
                            int ordinal = resultSet.getInt("chapter_ordinal");
                            return new PlaybackChapter(
                                    chapterId,
                                    ordinal,
                                    resultSet.getString("display_title"),
                                    resultSet.getLong("start_ms"),
                                    resultSet.getLong("duration_ms"),
                                    parts(
                                            listenerId,
                                            header.audiobookId(),
                                            header.assetVersionId(),
                                            chapterId,
                                            ordinal));
                        },
                        listenerId,
                        header.assetVersionId());
        if (chapters.isEmpty()) {
            throw new PrivateAudiobookUnavailableException();
        }
        return new PlaybackManifest(
                header.audiobookId(),
                header.assetVersionId(),
                header.conversionId(),
                header.sourceMediaType(),
                header.narratorVoice(),
                header.manifestDigest(),
                header.totalDurationMs(),
                new ResumePosition(header.positionMs(), header.positionVersion()),
                chapters);
    }

    private List<PlaybackPart> parts(
            UUID listenerId,
            UUID audiobookId,
            UUID assetVersionId,
            UUID chapterId,
            int chapterOrdinal) {
        List<PlaybackPart> chapterParts =
                jdbcTemplate.query(
                        """
                        SELECT part_id, part_ordinal, object_key, byte_length, duration_ms, mime_type, sha256
                        FROM library.final_asset_part
                        WHERE listener_id = ? AND asset_version_id = ? AND chapter_id = ?
                        ORDER BY part_ordinal
                        """,
                        (resultSet, row) -> {
                            UUID partId = resultSet.getObject("part_id", UUID.class);
                            int partOrdinal = resultSet.getInt("part_ordinal");
                            String sha256 = resultSet.getString("sha256");
                            String expectedObjectKey =
                                    "audiobooks/"
                                            + audiobookId
                                            + "/assets/"
                                            + assetVersionId
                                            + "/chapters/"
                                            + chapterOrdinal
                                            + "/parts/"
                                            + partOrdinal
                                            + '-'
                                            + sha256
                                            + ".mp3";
                            if (!expectedObjectKey.equals(resultSet.getString("object_key"))) {
                                throw new PrivateAudiobookUnavailableException();
                            }
                            return new PlaybackPart(
                                    partId,
                                    partOrdinal,
                                    resultSet.getLong("byte_length"),
                                    resultSet.getLong("duration_ms"),
                                    resultSet.getString("mime_type"),
                                    "sha256:" + sha256,
                                    "/api/v1/audiobooks/"
                                            + audiobookId
                                            + "/asset-versions/"
                                            + assetVersionId
                                            + "/parts/"
                                            + partId
                                            + "/media");
                        },
                        listenerId,
                        assetVersionId,
                        chapterId);
        if (chapterParts.isEmpty()) {
            throw new PrivateAudiobookUnavailableException();
        }
        return chapterParts;
    }

    @Override
    @Transactional
    public MediaResponse media(
            UUID listenerId,
            UUID audiobookId,
            UUID assetVersionId,
            UUID partId,
            String rangeHeader,
            String ifRangeHeader,
            boolean headRequest) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(audiobookId, "audiobookId");
        Objects.requireNonNull(assetVersionId, "assetVersionId");
        Objects.requireNonNull(partId, "partId");
        List<StoredMedia> matches =
                jdbcTemplate.query(
                        """
                        SELECT part.object_key, part.mime_type, part.byte_length, part.sha256,
                               part.chapter_ordinal, part.part_ordinal
                        FROM library.private_audiobook pa
                        JOIN library.audiobook_asset_version av
                          ON av.asset_version_id = pa.current_asset_version_id
                         AND av.audiobook_id = pa.audiobook_id
                         AND av.listener_id = pa.listener_id
                        JOIN library.final_asset_part part
                         ON part.asset_version_id = av.asset_version_id
                         AND part.listener_id = av.listener_id
                        JOIN workflow.audiobook_conversion conversion
                          ON conversion.conversion_id = pa.conversion_id
                         AND conversion.listener_id = pa.listener_id
                        JOIN listener_identity listener ON listener.listener_id = pa.listener_id
                        WHERE pa.listener_id = ?
                          AND pa.audiobook_id = ?
                          AND av.asset_version_id = ?
                          AND part.part_id = ?
                          AND conversion.state = 'FINALIZED'
                          AND pa.availability = 'AVAILABLE'
                          AND listener.access_state = 'ACTIVE'
                        FOR SHARE OF pa, av, part, listener
                        """,
                        (resultSet, row) ->
                                new StoredMedia(
                                        resultSet.getString("object_key"),
                                        resultSet.getString("mime_type"),
                                        resultSet.getLong("byte_length"),
                                        resultSet.getString("sha256"),
                                        resultSet.getInt("chapter_ordinal"),
                                        resultSet.getInt("part_ordinal")),
                        listenerId,
                        audiobookId,
                        assetVersionId,
                        partId);
        if (matches.isEmpty()) {
            throw new PrivateAudiobookUnavailableException();
        }
        StoredMedia media = matches.getFirst();
        String expectedObjectKey =
                "audiobooks/"
                        + audiobookId
                        + "/assets/"
                        + assetVersionId
                        + "/chapters/"
                        + media.chapterOrdinal()
                        + "/parts/"
                        + media.partOrdinal()
                        + '-'
                        + media.sha256()
                        + ".mp3";
        if (!expectedObjectKey.equals(media.objectKey())) {
            throw new PrivateAudiobookUnavailableException();
        }
        String entityTag = "sha256:" + media.sha256();
        boolean honorRange =
                rangeHeader != null
                        && (ifRangeHeader == null || ifRangeHeader.equals(quoted(entityTag)));
        try {
            byte[] completeContent = verifiedContent(media);
            if (headRequest) {
                return new MediaResponse(
                        media.mimeType(), entityTag, media.byteLength(), null, null, new byte[0]);
            }
            if (honorRange) {
                HttpByteRange range = HttpByteRange.parse(rangeHeader, media.byteLength());
                byte[] content =
                        Arrays.copyOfRange(
                                completeContent,
                                Math.toIntExact(range.start()),
                                Math.toIntExact(range.end() + 1));
                return new MediaResponse(
                        media.mimeType(),
                        entityTag,
                        media.byteLength(),
                        range.start(),
                        range.end(),
                        content);
            }
            return new MediaResponse(
                    media.mimeType(), entityTag, media.byteLength(), null, null, completeContent);
        } catch (IOException | ArithmeticException exception) {
            throw new PrivateAudiobookUnavailableException();
        }
    }

    private byte[] verifiedContent(StoredMedia media) throws IOException {
        byte[] content = assetStore.readFinal(media.objectKey());
        if (content.length != media.byteLength()
                || !MessageDigest.isEqual(
                        sha256(content).getBytes(StandardCharsets.US_ASCII),
                        media.sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("Audiobook asset integrity check failed");
        }
        return content;
    }

    private static String quoted(String entityTag) {
        return '"' + entityTag + '"';
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    @Transactional
    public ResumePosition updatePosition(UpdatePosition command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.audiobookId(), "audiobookId");
        Objects.requireNonNull(command.assetVersionId(), "assetVersionId");
        if (command.positionMs() < 0 || command.expectedVersion() < 0) {
            throw new PlaybackPositionRejectedException();
        }
        if (command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 200) {
            throw new PlaybackPositionRejectedException();
        }
        List<PositionAccess> grants =
                jdbcTemplate.query(
                        """
                        SELECT av.total_duration_ms, position.position_ms, position.version
                        FROM library.private_audiobook pa
                        JOIN library.audiobook_asset_version av
                          ON av.asset_version_id = pa.current_asset_version_id
                         AND av.audiobook_id = pa.audiobook_id
                         AND av.listener_id = pa.listener_id
                        JOIN listener_identity listener ON listener.listener_id = pa.listener_id
                        JOIN workflow.audiobook_conversion conversion
                          ON conversion.conversion_id = pa.conversion_id
                         AND conversion.listener_id = pa.listener_id
                        LEFT JOIN library.playback_position position
                          ON position.listener_id = pa.listener_id
                         AND position.audiobook_id = pa.audiobook_id
                         AND position.asset_version_id = av.asset_version_id
                        WHERE pa.listener_id = ?
                          AND pa.audiobook_id = ?
                          AND av.asset_version_id = ?
                          AND conversion.state = 'FINALIZED'
                          AND pa.availability = 'AVAILABLE'
                          AND listener.access_state = 'ACTIVE'
                        FOR UPDATE OF pa, listener
                        """,
                        (resultSet, row) ->
                                new PositionAccess(
                                        resultSet.getLong("total_duration_ms"),
                                        resultSet.getObject("version", Long.class)),
                        command.listenerId(),
                        command.audiobookId(),
                        command.assetVersionId());
        if (grants.isEmpty()) {
            throw new PrivateAudiobookUnavailableException();
        }
        PositionAccess grant = grants.getFirst();
        if (command.positionMs() > grant.totalDurationMs()) {
            throw new PlaybackPositionRejectedException();
        }
        String fingerprint =
                sha256(
                        (command.audiobookId()
                                        + "|"
                                        + command.assetVersionId()
                                        + "|"
                                        + command.positionMs()
                                        + "|"
                                        + command.expectedVersion())
                                .getBytes(StandardCharsets.UTF_8));
        List<StoredPositionOperation> replays =
                jdbcTemplate.query(
                        """
                        SELECT audiobook_id, asset_version_id, request_fingerprint,
                               position_ms, result_version
                        FROM library.playback_position_operation
                        WHERE listener_id = ? AND operation_key = ?
                        """,
                        (resultSet, row) ->
                                new StoredPositionOperation(
                                        resultSet.getObject("audiobook_id", UUID.class),
                                        resultSet.getObject("asset_version_id", UUID.class),
                                        resultSet.getString("request_fingerprint"),
                                        resultSet.getLong("position_ms"),
                                        resultSet.getLong("result_version")),
                        command.listenerId(),
                        command.idempotencyKey());
        if (!replays.isEmpty()) {
            StoredPositionOperation replay = replays.getFirst();
            if (!replay.audiobookId().equals(command.audiobookId())
                    || !replay.assetVersionId().equals(command.assetVersionId())
                    || !MessageDigest.isEqual(
                            replay.requestFingerprint().getBytes(StandardCharsets.US_ASCII),
                            fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new PlaybackPositionRequestConflictException();
            }
            return new ResumePosition(replay.positionMs(), replay.resultVersion());
        }
        long currentVersion = grant.version() == null ? 0 : grant.version();
        if (currentVersion != command.expectedVersion()) {
            throw new PlaybackPositionConflictException();
        }
        long resultVersion = Math.addExact(currentVersion, 1);
        Timestamp now = Timestamp.from(identityClock.instant());
        if (grant.version() == null) {
            jdbcTemplate.update(
                    """
                    INSERT INTO library.playback_position (
                        listener_id, audiobook_id, asset_version_id,
                        position_ms, version, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    command.listenerId(),
                    command.audiobookId(),
                    command.assetVersionId(),
                    command.positionMs(),
                    resultVersion,
                    now);
        } else {
            int updated =
                    jdbcTemplate.update(
                            """
                            UPDATE library.playback_position
                            SET position_ms = ?, version = ?, updated_at = ?
                            WHERE listener_id = ? AND audiobook_id = ?
                              AND asset_version_id = ? AND version = ?
                            """,
                            command.positionMs(),
                            resultVersion,
                            now,
                            command.listenerId(),
                            command.audiobookId(),
                            command.assetVersionId(),
                            currentVersion);
            if (updated != 1) {
                throw new PlaybackPositionConflictException();
            }
        }
        jdbcTemplate.update(
                """
                INSERT INTO library.playback_position_operation (
                    operation_key, listener_id, audiobook_id, asset_version_id,
                    request_fingerprint, position_ms, result_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                command.idempotencyKey(),
                command.listenerId(),
                command.audiobookId(),
                command.assetVersionId(),
                fingerprint,
                command.positionMs(),
                resultVersion,
                now);
        return new ResumePosition(command.positionMs(), resultVersion);
    }

    @Override
    public void publish(Publication publication) {
        Timestamp now = Timestamp.from(publication.createdAt());
        jdbcTemplate.update(
                """
                INSERT INTO library.private_audiobook (
                    audiobook_id, listener_id, conversion_id, availability, created_at
                ) VALUES (?, ?, ?, 'AVAILABLE', ?)
                """,
                publication.audiobookId(),
                publication.listenerId(),
                publication.conversionId(),
                now);
        jdbcTemplate.update(
                """
                INSERT INTO library.audiobook_asset_version (
                    asset_version_id, audiobook_id, listener_id, generation_recipe_id,
                    recipe_digest, manifest_object_key, manifest_digest,
                    packaging_profile_version, total_duration_ms, total_bytes,
                    integrated_loudness_lufs, true_peak_dbtp, applied_gain_db, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publication.assetVersionId(),
                publication.audiobookId(),
                publication.listenerId(),
                publication.recipeId(),
                publication.recipeDigest(),
                publication.manifestObjectKey(),
                publication.manifestDigest(),
                publication.packagingProfileVersion(),
                publication.totalDurationMs(),
                publication.totalBytes(),
                publication.integratedLoudnessLufs(),
                publication.truePeakDbtp(),
                publication.appliedGainDb(),
                now);
        for (Chapter chapter : publication.chapters()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO library.audiobook_chapter (
                        chapter_id, asset_version_id, listener_id, chapter_ordinal,
                        display_title, start_ms, duration_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    chapter.chapterId(),
                    publication.assetVersionId(),
                    publication.listenerId(),
                    chapter.ordinal(),
                    chapter.displayTitle(),
                    chapter.startMs(),
                    chapter.durationMs());
            for (Part part : chapter.parts()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO library.final_asset_part (
                            part_id, chapter_id, asset_version_id, listener_id,
                            chapter_ordinal, part_ordinal, object_key, mime_type,
                            byte_length, duration_ms, sha256
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        part.partId(),
                        chapter.chapterId(),
                        publication.assetVersionId(),
                        publication.listenerId(),
                        chapter.ordinal(),
                        part.ordinal(),
                        part.objectKey(),
                        part.mimeType(),
                        part.byteLength(),
                        part.durationMs(),
                        part.sha256());
            }
        }
        jdbcTemplate.update(
                """
                UPDATE library.private_audiobook
                SET current_asset_version_id = ?
                WHERE audiobook_id = ? AND listener_id = ?
                """,
                publication.assetVersionId(),
                publication.audiobookId(),
                publication.listenerId());
    }

    private record ManifestHeader(
            UUID audiobookId,
            UUID assetVersionId,
            UUID conversionId,
            String sourceMediaType,
            String narratorVoice,
            String manifestObjectKey,
            String manifestDigest,
            long totalDurationMs,
            long positionMs,
            long positionVersion) {}

    private record StoredMedia(
            String objectKey,
            String mimeType,
            long byteLength,
            String sha256,
            int chapterOrdinal,
            int partOrdinal) {}

    private record PositionAccess(long totalDurationMs, Long version) {}

    private record StoredPositionOperation(
            UUID audiobookId,
            UUID assetVersionId,
            String requestFingerprint,
            long positionMs,
            long resultVersion) {}
}
