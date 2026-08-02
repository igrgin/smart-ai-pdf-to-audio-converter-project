package dev.audiobook.platform.retention.deletion.persistence;

import static dev.audiobook.platform.retention.deletion.DeletionRequest.*;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.retention.RetentionProperties;
import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionConflictException;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionPreconditionFailedException;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionUnavailableException;
import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

@Component
@RequiredArgsConstructor
public class JdbcDeletionRequestPersistence implements DeletionRequestPersistence {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final RetentionProperties properties;
    private final RetentionDigest retentionDigest;
    private final TombstoneRegistry tombstoneRegistry;
    private final Clock identityClock;

    @Override
    @Transactional
    public DeletionReceipt deleteAudiobook(DeleteAudiobookCommand command) {
        validate(command);
        String subjectDigest = retentionDigest.digest("listener", command.listenerId().toString());
        String resourceDigest = retentionDigest.digest("audiobook", command.audiobookId().toString());
        String operationDigest =
                retentionDigest.digest("deletion-operation", command.operationKey());
        String fingerprint =
                sha256(
                        "AUDIOBOOK\n"
                                + command.audiobookId()
                                + "\n"
                                + command.expectedVersion());

        StoredRequest replay = operationReplay(subjectDigest, operationDigest);
        if (replay != null) {
            if (!replay.requestFingerprint().equals(fingerprint)) {
                throw new DeletionConflictException();
            }
            return replay.receipt();
        }
        StoredRequest active = latestRequest(subjectDigest, resourceDigest);
        if (active != null) {
            return active.receipt();
        }

        List<Long> versions =
                jdbcTemplate.query(
                        """
                        SELECT audiobook.version
                        FROM library.private_audiobook audiobook
                        JOIN listener_identity listener ON listener.listener_id = audiobook.listener_id
                        WHERE audiobook.listener_id = ?
                          AND audiobook.audiobook_id = ?
                          AND listener.access_state = 'ACTIVE'
                          AND audiobook.availability <> 'ERASED'
                        FOR UPDATE OF audiobook, listener
                        """,
                        (resultSet, row) -> resultSet.getLong("version"),
                        command.listenerId(),
                        command.audiobookId());
        if (versions.isEmpty()) {
            throw new DeletionUnavailableException();
        }
        if (versions.getFirst() != command.expectedVersion()) {
            throw new DeletionPreconditionFailedException();
        }

        Instant now = databaseTime(identityClock.instant());
        UUID requestId = identifierGenerator.generate();
        StoredRequest request =
                new StoredRequest(
                        requestId,
                        DeletionScope.AUDIOBOOK,
                        DeletionState.ACCEPTED,
                        fingerprint,
                        now,
                        databaseTime(now.plus(properties.quickErasureTarget())),
                        databaseTime(now.plus(properties.liveErasureDeadline())),
                        databaseTime(now.plus(properties.providerEvidenceDeadline())),
                        databaseTime(now.plus(properties.backupExpiry())),
                        null);
        insertRequest(request, subjectDigest, resourceDigest, operationDigest, now);
        UUID tombstoneId = identifierGenerator.generate();
        jdbcTemplate.update(
                """
                INSERT INTO retention.deletion_tombstone (
                    tombstone_id, request_id, scope, subject_digest, resource_digest, created_at
                ) VALUES (?, ?, 'AUDIOBOOK', ?, ?, ?)
                """,
                tombstoneId,
                requestId,
                subjectDigest,
                resourceDigest,
                Timestamp.from(now));
        tombstoneRegistry.append(
                new TombstoneRegistry.TombstoneRecord(
                        tombstoneId,
                        requestId,
                        DeletionScope.AUDIOBOOK.name(),
                        subjectDigest,
                        resourceDigest,
                        now));
        jdbcTemplate.update(
                """
                INSERT INTO offline_access.authorization_generation (
                    listener_id, audiobook_id, generation, updated_at
                ) VALUES (?, ?, 1, ?)
                ON CONFLICT (listener_id, audiobook_id) DO NOTHING
                """,
                command.listenerId(),
                command.audiobookId(),
                Timestamp.from(now));
        int denied =
                jdbcTemplate.update(
                        """
                        UPDATE library.private_audiobook
                        SET availability = 'DELETING', version = version + 1
                        WHERE listener_id = ? AND audiobook_id = ? AND version = ?
                        """,
                        command.listenerId(),
                        command.audiobookId(),
                        command.expectedVersion());
        if (denied != 1) {
            throw new DeletionPreconditionFailedException();
        }
        createAudiobookObligations(
                request, command.listenerId(), command.audiobookId(), now, true);
        return request.receipt();
    }

    @Override
    @Transactional
    public DeletionReceipt deleteAccount(DeleteAccountCommand command) {
        validate(command);
        String subjectDigest = retentionDigest.digest("listener", command.listenerId().toString());
        String operationDigest =
                retentionDigest.digest("deletion-operation", command.operationKey());
        String fingerprint = sha256("ACCOUNT");
        StoredRequest replay = operationReplay(subjectDigest, operationDigest);
        if (replay != null) {
            if (!replay.requestFingerprint().equals(fingerprint)) {
                throw new DeletionConflictException();
            }
            return replay.receipt();
        }
        StoredRequest active = latestAccountRequest(subjectDigest);
        if (active != null) {
            return active.receipt();
        }

        List<UUID> listeners =
                jdbcTemplate.query(
                        """
                        SELECT listener_id
                        FROM listener_identity
                        WHERE listener_id = ? AND access_state = 'ACTIVE'
                        FOR UPDATE
                        """,
                        (resultSet, row) -> resultSet.getObject("listener_id", UUID.class),
                        command.listenerId());
        if (listeners.isEmpty()) {
            throw new DeletionUnavailableException();
        }
        List<ExternalIdentitySource> externalIdentities =
                jdbcTemplate.query(
                        """
                        SELECT issuer, subject
                        FROM external_identity_link
                        WHERE listener_id = ?
                        FOR UPDATE
                        """,
                        (resultSet, row) ->
                                new ExternalIdentitySource(
                                        resultSet.getString("issuer"),
                                        resultSet.getString("subject")),
                        command.listenerId());
        List<UUID> audiobookIds =
                jdbcTemplate.queryForList(
                        "SELECT audiobook_id FROM library.private_audiobook WHERE listener_id = ?",
                        UUID.class,
                        command.listenerId());

        Instant now = databaseTime(identityClock.instant());
        UUID requestId = identifierGenerator.generate();
        StoredRequest request =
                new StoredRequest(
                        requestId,
                        DeletionScope.ACCOUNT,
                        DeletionState.ACCEPTED,
                        fingerprint,
                        now,
                        databaseTime(now.plus(properties.quickErasureTarget())),
                        databaseTime(now.plus(properties.liveErasureDeadline())),
                        databaseTime(now.plus(properties.providerEvidenceDeadline())),
                        databaseTime(now.plus(properties.backupExpiry())),
                        null);
        insertRequest(request, subjectDigest, null, operationDigest, now);
        UUID tombstoneId = identifierGenerator.generate();
        jdbcTemplate.update(
                """
                INSERT INTO retention.deletion_tombstone (
                    tombstone_id, request_id, scope, subject_digest, resource_digest, created_at
                ) VALUES (?, ?, 'ACCOUNT', ?, NULL, ?)
                """,
                tombstoneId,
                requestId,
                subjectDigest,
                Timestamp.from(now));
        tombstoneRegistry.append(
                new TombstoneRegistry.TombstoneRecord(
                        tombstoneId,
                        requestId,
                        DeletionScope.ACCOUNT.name(),
                        subjectDigest,
                        null,
                        now));
        for (ExternalIdentitySource identity : externalIdentities) {
            jdbcTemplate.update(
                    """
                    INSERT INTO retention.external_identity_tombstone (
                        identity_digest, request_id, created_at
                    ) VALUES (?, ?, ?)
                    ON CONFLICT (identity_digest) DO NOTHING
                    """,
                    retentionDigest.digest(
                            "external-identity", identity.issuer() + "\n" + identity.subject()),
                    requestId,
                    Timestamp.from(now));
        }
        jdbcTemplate.update(
                """
                INSERT INTO offline_access.authorization_generation (
                    listener_id, audiobook_id, generation, updated_at
                )
                SELECT listener_id, audiobook_id, 1, ?
                FROM library.private_audiobook
                WHERE listener_id = ?
                ON CONFLICT (listener_id, audiobook_id) DO NOTHING
                """,
                Timestamp.from(now),
                command.listenerId());
        jdbcTemplate.update(
                """
                UPDATE listener_identity
                SET access_state = 'DELETED', display_name = 'Deleted Listener', contact_email = NULL
                WHERE listener_id = ?
                """,
                command.listenerId());
        jdbcTemplate.update(
                """
                UPDATE library.private_audiobook
                SET availability = 'DELETING', version = version + 1
                WHERE listener_id = ? AND availability <> 'ERASED'
                """,
                command.listenerId());
        jdbcTemplate.update(
                "DELETE FROM external_identity_link WHERE listener_id = ?", command.listenerId());
        jdbcTemplate.update(
                "DELETE FROM spring_session WHERE principal_name = ?",
                command.listenerId().toString());

        for (UUID audiobookId : audiobookIds) {
            createAudiobookObligations(request, command.listenerId(), audiobookId, now, false);
        }
        createAccountOnlyAssetObligations(request, command.listenerId(), now);
        insertObligation(
                request.requestId(),
                "RELATIONAL",
                "RELATIONAL_PRIVATE_DATA",
                "ACCOUNT\n" + command.listenerId(),
                request.quickErasureDueAt(),
                request.liveErasureDueAt(),
                now);
        return request.receipt();
    }

    @Override
    @Transactional(readOnly = true)
    public DeletionStatus status(UUID listenerId, UUID requestId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(requestId, "requestId");
        String subjectDigest = retentionDigest.digest("listener", listenerId.toString());
        List<DeletionStatus> matches =
                jdbcTemplate.query(
                        """
                        SELECT request.request_id, request.scope, request.state,
                               request.requested_at, request.quick_erasure_due_at,
                               request.live_erasure_due_at, request.provider_evidence_due_at,
                               request.backup_expires_at, request.failure_code,
                               count(obligation.obligation_id) AS total_obligations,
                               count(obligation.obligation_id) FILTER (
                                   WHERE obligation.state = 'COMPLETED'
                               ) AS completed_obligations
                        FROM retention.deletion_request request
                        LEFT JOIN retention.erasure_obligation obligation
                          ON obligation.request_id = request.request_id
                        WHERE request.request_id = ? AND request.subject_digest = ?
                        GROUP BY request.request_id
                        """,
                        (resultSet, row) ->
                                new DeletionStatus(
                                        resultSet.getObject("request_id", UUID.class),
                                        DeletionScope.valueOf(resultSet.getString("scope")),
                                        DeletionState.valueOf(resultSet.getString("state")),
                                        resultSet.getTimestamp("requested_at").toInstant(),
                                        resultSet.getTimestamp("quick_erasure_due_at").toInstant(),
                                        resultSet.getTimestamp("live_erasure_due_at").toInstant(),
                                        resultSet
                                                .getTimestamp("provider_evidence_due_at")
                                                .toInstant(),
                                        resultSet.getTimestamp("backup_expires_at").toInstant(),
                                        resultSet.getInt("completed_obligations"),
                                        resultSet.getInt("total_obligations"),
                                        resultSet.getString("failure_code")),
                        requestId,
                        subjectDigest);
        if (matches.isEmpty()) {
            throw new DeletionUnavailableException();
        }
        return matches.getFirst();
    }

    private void createAudiobookObligations(
            StoredRequest request,
            UUID listenerId,
            UUID audiobookId,
            Instant now,
            boolean includeRelational) {
        Instant quickDue = request.quickErasureDueAt();
        Instant liveDue = request.liveErasureDueAt();
        List<AssetLocator> assets = new ArrayList<>();
        assets.addAll(
                jdbcTemplate.query(
                        """
                        SELECT 'AUDIO_FINAL' AS asset_kind, asset.manifest_object_key AS locator
                        FROM library.audiobook_asset_version asset
                        WHERE asset.listener_id = ? AND asset.audiobook_id = ?
                        UNION ALL
                        SELECT 'AUDIO_FINAL', part.object_key
                        FROM library.final_asset_part part
                        JOIN library.audiobook_asset_version asset
                          ON asset.asset_version_id = part.asset_version_id
                        WHERE part.listener_id = ? AND asset.audiobook_id = ?
                        """,
                        (resultSet, row) ->
                                new AssetLocator(
                                        resultSet.getString("asset_kind"),
                                        resultSet.getString("locator")),
                        listenerId,
                        audiobookId,
                        listenerId,
                        audiobookId));
        assets.addAll(
                jdbcTemplate.query(
                        """
                        SELECT 'NARRATION_PLAN' AS asset_kind, plan.working_asset_ref AS locator
                        FROM narration.narration_plan plan
                        JOIN library.private_audiobook audiobook
                          ON audiobook.conversion_id = plan.conversion_id
                        WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                        UNION ALL
                        SELECT 'NARRATION_REVIEW', review.working_asset_ref
                        FROM narration.narration_review_decision review
                        JOIN library.private_audiobook audiobook
                          ON audiobook.conversion_id = review.conversion_id
                        WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                        UNION ALL
                        SELECT 'AUDIO_WORKING', segment.spoken_text_ref
                        FROM generation.speech_segment segment
                        JOIN library.private_audiobook audiobook
                          ON audiobook.conversion_id = segment.conversion_id
                        WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                        UNION ALL
                        SELECT 'AUDIO_WORKING', accepted.pcm_object_key
                        FROM generation.accepted_segment accepted
                        JOIN library.private_audiobook audiobook
                          ON audiobook.conversion_id = accepted.conversion_id
                        WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                        """,
                        (resultSet, row) ->
                                new AssetLocator(
                                        resultSet.getString("asset_kind"),
                                        resultSet.getString("locator")),
                        listenerId,
                        audiobookId,
                        listenerId,
                        audiobookId,
                        listenerId,
                        audiobookId,
                        listenerId,
                        audiobookId));
        assets.addAll(
                jdbcTemplate.query(
                        """
                        SELECT 'QUARANTINE_OBJECT' AS asset_kind, quarantine.object_id::text AS locator
                        FROM admission.quarantine_object quarantine
                        JOIN admission.source_publication publication
                          ON publication.submission_id = quarantine.submission_id
                        JOIN workflow.audiobook_conversion conversion
                          ON conversion.source_publication_id = publication.source_publication_id
                        JOIN library.private_audiobook audiobook
                          ON audiobook.conversion_id = conversion.conversion_id
                        WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                        """,
                        (resultSet, row) ->
                                new AssetLocator(
                                        resultSet.getString("asset_kind"),
                                        resultSet.getString("locator")),
                        listenerId,
                        audiobookId));
        for (AssetLocator asset : assets) {
            String category =
                    asset.assetKind().equals("AUDIO_FINAL")
                            ? "FINAL_ASSET"
                            : "WORKING_ASSET";
            insertObligation(
                    request.requestId(),
                    category,
                    asset.assetKind(),
                    asset.locator(),
                    quickDue,
                    liveDue,
                    now);
        }
        List<String> providerOperations =
                jdbcTemplate.queryForList(
                        """
                        SELECT evidence.operation_id
                        FROM provider.operation_evidence evidence
                        JOIN narration.generation_recipe recipe
                          ON recipe.recipe_id = evidence.generation_recipe_id
                        JOIN library.private_audiobook audiobook
                          ON audiobook.conversion_id = recipe.conversion_id
                        WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                        """,
                        String.class,
                        listenerId,
                        audiobookId);
        for (String operation : providerOperations) {
            insertObligation(
                    request.requestId(),
                    "PROVIDER",
                    "PROVIDER_EVIDENCE",
                    operation,
                    request.providerEvidenceDueAt(),
                    request.providerEvidenceDueAt(),
                    now);
        }
        if (includeRelational) {
            insertObligation(
                    request.requestId(),
                    "RELATIONAL",
                    "RELATIONAL_PRIVATE_DATA",
                    "AUDIOBOOK\n" + listenerId + "\n" + audiobookId,
                    quickDue,
                    liveDue,
                    now);
        }
    }

    private void createAccountOnlyAssetObligations(
            StoredRequest request, UUID listenerId, Instant now) {
        List<AssetLocator> assets =
                jdbcTemplate.query(
                        """
                        SELECT 'NARRATION_PLAN' AS asset_kind, plan.working_asset_ref AS locator
                        FROM narration.narration_plan plan
                        WHERE plan.listener_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM library.private_audiobook audiobook
                              WHERE audiobook.conversion_id = plan.conversion_id
                          )
                        UNION ALL
                        SELECT 'NARRATION_REVIEW', review.working_asset_ref
                        FROM narration.narration_review_decision review
                        WHERE review.listener_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM library.private_audiobook audiobook
                              WHERE audiobook.conversion_id = review.conversion_id
                          )
                        UNION ALL
                        SELECT 'AUDIO_WORKING', segment.spoken_text_ref
                        FROM generation.speech_segment segment
                        WHERE segment.listener_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM library.private_audiobook audiobook
                              WHERE audiobook.conversion_id = segment.conversion_id
                          )
                        UNION ALL
                        SELECT 'AUDIO_WORKING', accepted.pcm_object_key
                        FROM generation.accepted_segment accepted
                        WHERE accepted.listener_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM library.private_audiobook audiobook
                              WHERE audiobook.conversion_id = accepted.conversion_id
                          )
                        UNION ALL
                        SELECT 'QUARANTINE_OBJECT', quarantine.object_id::text
                        FROM admission.quarantine_object quarantine
                        WHERE quarantine.listener_id = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM admission.source_publication publication
                              JOIN workflow.audiobook_conversion conversion
                                ON conversion.source_publication_id = publication.source_publication_id
                              JOIN library.private_audiobook audiobook
                                ON audiobook.conversion_id = conversion.conversion_id
                              WHERE publication.submission_id = quarantine.submission_id
                          )
                        """,
                        (resultSet, row) ->
                                new AssetLocator(
                                        resultSet.getString("asset_kind"),
                                        resultSet.getString("locator")),
                        listenerId,
                        listenerId,
                        listenerId,
                        listenerId,
                        listenerId);
        for (AssetLocator asset : assets) {
            insertObligation(
                    request.requestId(),
                    "WORKING_ASSET",
                    asset.assetKind(),
                    asset.locator(),
                    request.quickErasureDueAt(),
                    request.liveErasureDueAt(),
                    now);
        }
        List<String> providerOperations =
                jdbcTemplate.queryForList(
                        """
                        SELECT evidence.operation_id
                        FROM provider.operation_evidence evidence
                        JOIN narration.generation_recipe recipe
                          ON recipe.recipe_id = evidence.generation_recipe_id
                        WHERE recipe.listener_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM library.private_audiobook audiobook
                              WHERE audiobook.conversion_id = recipe.conversion_id
                          )
                        """,
                        String.class,
                        listenerId);
        for (String operation : providerOperations) {
            insertObligation(
                    request.requestId(),
                    "PROVIDER",
                    "PROVIDER_EVIDENCE",
                    operation,
                    request.providerEvidenceDueAt(),
                    request.providerEvidenceDueAt(),
                    now);
        }
    }

    private void insertRequest(
            StoredRequest request,
            String subjectDigest,
            String resourceDigest,
            String operationKey,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO retention.deletion_request (
                    request_id, scope, subject_digest, resource_digest, operation_key,
                    request_fingerprint, state, requested_at, quick_erasure_due_at,
                    live_erasure_due_at, provider_evidence_due_at, backup_expires_at,
                    evidence_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.requestId(),
                request.scope().name(),
                subjectDigest,
                resourceDigest,
                operationKey,
                request.requestFingerprint(),
                request.state().name(),
                Timestamp.from(request.requestedAt()),
                Timestamp.from(request.quickErasureDueAt()),
                Timestamp.from(request.liveErasureDueAt()),
                Timestamp.from(request.providerEvidenceDueAt()),
                Timestamp.from(request.backupExpiresAt()),
                Timestamp.from(databaseTime(now.plus(properties.evidenceRetention()))));
    }

    private void insertObligation(
            UUID requestId,
            String category,
            String kind,
            String locator,
            Instant dueAt,
            Instant hardDueAt,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO retention.erasure_obligation (
                    obligation_id, request_id, category, asset_kind, locator, locator_digest,
                    state, due_at, hard_due_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (request_id, asset_kind, locator_digest) DO NOTHING
                """,
                identifierGenerator.generate(),
                requestId,
                category,
                kind,
                locator,
                retentionDigest.digest("erasure-locator", kind + "\n" + locator),
                Timestamp.from(dueAt),
                Timestamp.from(hardDueAt),
                Timestamp.from(now));
    }

    private StoredRequest operationReplay(String subjectDigest, String operationKey) {
        List<StoredRequest> matches =
                jdbcTemplate.query(
                        requestSelect()
                                + " WHERE subject_digest = ? AND operation_key = ?",
                        this::storedRequest,
                        subjectDigest,
                        operationKey);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private StoredRequest latestRequest(String subjectDigest, String resourceDigest) {
        List<StoredRequest> matches =
                jdbcTemplate.query(
                        requestSelect()
                                + " WHERE subject_digest = ? AND resource_digest = ?"
                                + " ORDER BY requested_at DESC LIMIT 1",
                        this::storedRequest,
                        subjectDigest,
                        resourceDigest);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private StoredRequest latestAccountRequest(String subjectDigest) {
        List<StoredRequest> matches =
                jdbcTemplate.query(
                        requestSelect()
                                + " WHERE subject_digest = ? AND scope = 'ACCOUNT'"
                                + " ORDER BY requested_at DESC LIMIT 1",
                        this::storedRequest,
                        subjectDigest);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static String requestSelect() {
        return """
                SELECT request_id, scope, state, request_fingerprint, requested_at,
                       quick_erasure_due_at, live_erasure_due_at,
                       provider_evidence_due_at, backup_expires_at, failure_code
                FROM retention.deletion_request
                """;
    }

    private StoredRequest storedRequest(java.sql.ResultSet resultSet, int row)
            throws java.sql.SQLException {
        return new StoredRequest(
                resultSet.getObject("request_id", UUID.class),
                DeletionScope.valueOf(resultSet.getString("scope")),
                DeletionState.valueOf(resultSet.getString("state")),
                resultSet.getString("request_fingerprint"),
                resultSet.getTimestamp("requested_at").toInstant(),
                resultSet.getTimestamp("quick_erasure_due_at").toInstant(),
                resultSet.getTimestamp("live_erasure_due_at").toInstant(),
                resultSet.getTimestamp("provider_evidence_due_at").toInstant(),
                resultSet.getTimestamp("backup_expires_at").toInstant(),
                resultSet.getString("failure_code"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validate(DeleteAudiobookCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.audiobookId(), "audiobookId");
        if (command.expectedVersion() < 0) {
            throw new IllegalArgumentException("Expected audiobook version must not be negative");
        }
        if (command.operationKey() == null
                || command.operationKey().isBlank()
                || command.operationKey().length() > 200) {
            throw new IllegalArgumentException("Deletion idempotency key is invalid");
        }
    }

    private static void validate(DeleteAccountCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        if (command.operationKey() == null
                || command.operationKey().isBlank()
                || command.operationKey().length() > 200) {
            throw new IllegalArgumentException("Deletion idempotency key is invalid");
        }
    }

    private static Instant databaseTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private record AssetLocator(String assetKind, String locator) {}

    private record ExternalIdentitySource(String issuer, String subject) {}

    private record StoredRequest(
            UUID requestId,
            DeletionScope scope,
            DeletionState state,
            String requestFingerprint,
            Instant requestedAt,
            Instant quickErasureDueAt,
            Instant liveErasureDueAt,
            Instant providerEvidenceDueAt,
            Instant backupExpiresAt,
            String failureCode) {

        DeletionReceipt receipt() {
            return new DeletionReceipt(
                    requestId,
                    scope,
                    state,
                    requestedAt,
                    quickErasureDueAt,
                    liveErasureDueAt,
                    providerEvidenceDueAt,
                    backupExpiresAt);
        }
    }
}
