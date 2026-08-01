package dev.audiobook.platform.offline.internal;

import dev.audiobook.platform.library.PrivateAudiobookLibraryService;
import dev.audiobook.platform.offline.OfflineAccessProperties;
import dev.audiobook.platform.offline.OfflineAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OfflineAccessServiceImpl implements OfflineAccessService {

    private static final String PURPOSE = "OFFLINE_PLAYBACK";

    private final JdbcTemplate jdbcTemplate;
    private final PrivateAudiobookLibraryService libraryService;
    private final OfflineAuthorizationSigner authorizationSigner;
    private final OfflineAccessProperties properties;
    private final Clock identityClock;

    @Override
    @Transactional
    public OfflineCopyAuthorization issue(IssueAuthorization command) {
        validate(command);
        PrivateAudiobookLibraryService.PlaybackManifest playback = libraryService.manifest(
                command.listenerId(), command.audiobookId(), command.assetVersionId());
        OfflineManifest offlineManifest = offlineManifest(command.listenerId(), playback);
        Instant now = databaseTime(identityClock.instant());
        long generation = generation(command.listenerId(), command.audiobookId(), now);
        String fingerprint = fingerprint(command);
        List<StoredOperation> existing = jdbcTemplate.query(
                """
                SELECT request_fingerprint, installation_id, authorization_generation,
                       issued_at, expires_at, signed_payload, signature
                FROM offline_access.authorization_operation
                WHERE listener_id = ? AND operation_key = ?
                """,
                (resultSet, row) -> new StoredOperation(
                        resultSet.getString("request_fingerprint"),
                        resultSet.getObject("installation_id", UUID.class),
                        resultSet.getLong("authorization_generation"),
                        resultSet.getTimestamp("issued_at").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant(),
                        resultSet.getString("signed_payload"),
                        resultSet.getString("signature")),
                command.listenerId(),
                command.idempotencyKey());
        if (!existing.isEmpty()) {
            StoredOperation operation = existing.getFirst();
            if (!operation.requestFingerprint().equals(fingerprint)
                    || !operation.installationId().equals(command.installationId())
                    || operation.authorizationGeneration() != generation) {
                throw new OfflineAuthorizationConflictException();
            }
            AuthorizationClaims claims = claims(command, operation.authorizationGeneration(),
                    operation.issuedAt(), operation.expiresAt());
            return new OfflineCopyAuthorization(
                    now,
                    authorizationSigner.restore(claims, operation.payload(), operation.signature()),
                    offlineManifest);
        }

        Instant expiresAt = databaseTime(now.plus(properties.authorizationValidity()));
        AuthorizationClaims claims = claims(command, generation, now, expiresAt);
        SignedAuthorization authorization = authorizationSigner.sign(claims);
        jdbcTemplate.update(
                """
                INSERT INTO offline_access.authorization_operation (
                    listener_id, operation_key, request_fingerprint, installation_id,
                    audiobook_id, asset_version_id, authorization_generation,
                    issued_at, expires_at, signed_payload, signature
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                command.listenerId(),
                command.idempotencyKey(),
                fingerprint,
                command.installationId(),
                command.audiobookId(),
                command.assetVersionId(),
                generation,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                authorization.payload(),
                authorization.signature());
        return new OfflineCopyAuthorization(now, authorization, offlineManifest);
    }

    @Override
    @Transactional
    public void revoke(UUID listenerId, UUID audiobookId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(audiobookId, "audiobookId");
        jdbcTemplate.update(
                """
                INSERT INTO offline_access.authorization_generation (
                    listener_id, audiobook_id, generation, updated_at
                ) VALUES (?, ?, 2, ?)
                ON CONFLICT (listener_id, audiobook_id) DO UPDATE
                SET generation = offline_access.authorization_generation.generation + 1,
                    updated_at = EXCLUDED.updated_at
                """,
                listenerId, audiobookId, Timestamp.from(identityClock.instant()));
    }

    private OfflineManifest offlineManifest(
            UUID listenerId,
            PrivateAudiobookLibraryService.PlaybackManifest playback) {
        List<OfflineChapter> chapters = new ArrayList<>();
        List<OfflinePart> parts = new ArrayList<>();
        long totalBytes = 0;
        for (PrivateAudiobookLibraryService.PlaybackChapter chapter : playback.chapters()) {
            List<UUID> partIds = new ArrayList<>();
            for (PrivateAudiobookLibraryService.PlaybackPart part : chapter.parts()) {
                PrivateAudiobookLibraryService.MediaResponse media = libraryService.media(
                        listenerId,
                        playback.audiobookId(),
                        playback.assetVersionId(),
                        part.partId(),
                        null,
                        null,
                        false);
                if (media.totalLength() != part.byteLength() || media.content().length != part.byteLength()) {
                    throw new IllegalStateException("Offline Copy source media length is inconsistent");
                }
                List<OfflineChunk> chunks = chunks(media.content());
                parts.add(new OfflinePart(
                        part.partId(),
                        part.ordinal(),
                        part.mimeType(),
                        part.byteLength(),
                        part.durationMs(),
                        part.entityTag(),
                        part.mediaUrl(),
                        chunks));
                partIds.add(part.partId());
                totalBytes = Math.addExact(totalBytes, part.byteLength());
            }
            chapters.add(new OfflineChapter(
                    chapter.chapterId(),
                    chapter.ordinal(),
                    chapter.title(),
                    chapter.startMs(),
                    chapter.durationMs(),
                    partIds));
        }
        return new OfflineManifest(
                playback.audiobookId(),
                playback.assetVersionId(),
                playback.manifestDigest(),
                playback.sourceMediaType(),
                playback.narratorVoice(),
                playback.totalDurationMs(),
                totalBytes,
                chapters,
                parts);
    }

    private List<OfflineChunk> chunks(byte[] content) {
        List<OfflineChunk> chunks = new ArrayList<>();
        int ordinal = 0;
        for (int start = 0; start < content.length; start += properties.chunkBytes()) {
            int endExclusive = Math.min(content.length, start + properties.chunkBytes());
            byte[] chunk = java.util.Arrays.copyOfRange(content, start, endExclusive);
            chunks.add(new OfflineChunk(ordinal++, start, endExclusive - 1L, chunk.length, sha256(chunk)));
        }
        return chunks;
    }

    private long generation(UUID listenerId, UUID audiobookId, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO offline_access.authorization_generation (
                    listener_id, audiobook_id, generation, updated_at
                ) VALUES (?, ?, 1, ?)
                ON CONFLICT (listener_id, audiobook_id) DO NOTHING
                """,
                listenerId, audiobookId, Timestamp.from(now));
        return jdbcTemplate.queryForObject(
                """
                SELECT generation
                FROM offline_access.authorization_generation
                WHERE listener_id = ? AND audiobook_id = ?
                FOR UPDATE
                """,
                Long.class,
                listenerId,
                audiobookId);
    }

    private static AuthorizationClaims claims(
            IssueAuthorization command,
            long generation,
            Instant issuedAt,
            Instant expiresAt) {
        return new AuthorizationClaims(
                command.listenerId(),
                command.installationId(),
                command.audiobookId(),
                command.assetVersionId(),
                generation,
                PURPOSE,
                issuedAt,
                expiresAt);
    }

    private static void validate(IssueAuthorization command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.installationId(), "installationId");
        Objects.requireNonNull(command.audiobookId(), "audiobookId");
        Objects.requireNonNull(command.assetVersionId(), "assetVersionId");
        if (command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 200) {
            throw new IllegalArgumentException("Idempotency key is invalid");
        }
    }

    private static String fingerprint(IssueAuthorization command) {
        return sha256((command.installationId() + "\n"
                + command.audiobookId() + "\n"
                + command.assetVersionId()).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static Instant databaseTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private record StoredOperation(
            String requestFingerprint,
            UUID installationId,
            long authorizationGeneration,
            Instant issuedAt,
            Instant expiresAt,
            String payload,
            String signature) {
    }
}
