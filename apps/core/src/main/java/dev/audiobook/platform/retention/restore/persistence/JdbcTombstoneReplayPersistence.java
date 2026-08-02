package dev.audiobook.platform.retention.restore.persistence;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.RetentionProperties;
import dev.audiobook.platform.retention.tombstone.TombstoneRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JdbcTombstoneReplayPersistence implements TombstoneReplayPersistence {

    private final JdbcTemplate jdbcTemplate;
    private final RetentionDigest retentionDigest;
    private final RetentionProperties properties;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final Clock identityClock;

    @Override
    public void importTombstone(TombstoneRegistry.TombstoneRecord tombstone) {
        Instant createdAt = databaseTime(tombstone.createdAt());
        jdbcTemplate.update(
                """
                INSERT INTO retention.deletion_request (
                    request_id, scope, subject_digest, resource_digest, operation_key,
                    request_fingerprint, state, requested_at, quick_erasure_due_at,
                    live_erasure_due_at, provider_evidence_due_at, backup_expires_at,
                    evidence_expires_at, live_erased_at, provider_evidenced_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (request_id) DO NOTHING
                """,
                tombstone.requestId(),
                tombstone.scope(),
                tombstone.subjectDigest(),
                tombstone.resourceDigest(),
                retentionDigest.digest(
                        "deletion-operation", "registry-import:" + tombstone.requestId()),
                sha256("REGISTRY_IMPORT\n" + tombstone.tombstoneId()),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(properties.quickErasureTarget())),
                Timestamp.from(createdAt.plus(properties.liveErasureDeadline())),
                Timestamp.from(createdAt.plus(properties.providerEvidenceDeadline())),
                Timestamp.from(createdAt.plus(properties.backupExpiry())),
                Timestamp.from(createdAt.plus(properties.evidenceRetention())),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        jdbcTemplate.update(
                """
                INSERT INTO retention.deletion_tombstone (
                    tombstone_id, request_id, scope, subject_digest, resource_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                tombstone.tombstoneId(),
                tombstone.requestId(),
                tombstone.scope(),
                tombstone.subjectDigest(),
                tombstone.resourceDigest(),
                Timestamp.from(createdAt));
    }

    @Override
    public List<Tombstone> tombstones() {
        return jdbcTemplate.query(
                "SELECT tombstone_id, scope, subject_digest, resource_digest"
                        + " FROM retention.deletion_tombstone ORDER BY created_at",
                (resultSet, row) ->
                        new Tombstone(
                                resultSet.getObject("tombstone_id", UUID.class),
                                resultSet.getString("scope"),
                                resultSet.getString("subject_digest"),
                                resultSet.getString("resource_digest")));
    }

    @Override
    public List<ListenerReference> listeners() {
        return jdbcTemplate.query(
                "SELECT listener_id, access_state FROM listener_identity",
                (resultSet, row) ->
                        new ListenerReference(
                                resultSet.getObject("listener_id", UUID.class),
                                resultSet.getString("access_state")));
    }

    @Override
    public List<AudiobookReference> audiobooks() {
        return jdbcTemplate.query(
                "SELECT listener_id, audiobook_id, availability FROM library.private_audiobook",
                (resultSet, row) ->
                        new AudiobookReference(
                                resultSet.getObject("listener_id", UUID.class),
                                resultSet.getObject("audiobook_id", UUID.class),
                                resultSet.getString("availability")));
    }

    @Override
    public void denyAudiobook(UUID listenerId, UUID audiobookId) {
        Instant now = databaseTime(identityClock.instant());
        String accessState =
                jdbcTemplate.queryForObject(
                        "SELECT access_state FROM listener_identity WHERE listener_id = ? FOR UPDATE",
                        String.class,
                        listenerId);
        jdbcTemplate.update(
                """
                INSERT INTO offline_access.authorization_generation (
                    listener_id, audiobook_id, generation, updated_at
                ) VALUES (?, ?, 1, ?)
                ON CONFLICT (listener_id, audiobook_id) DO NOTHING
                """,
                listenerId,
                audiobookId,
                Timestamp.from(now));
        if (!"ACTIVE".equals(accessState)) {
            jdbcTemplate.update(
                    "UPDATE offline_access.authorization_generation"
                            + " SET generation = generation + 1, updated_at = ?"
                            + " WHERE listener_id = ? AND audiobook_id = ?",
                    Timestamp.from(now),
                    listenerId,
                    audiobookId);
        }
        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'DELETING',"
                        + " version = version + 1 WHERE listener_id = ? AND audiobook_id = ?",
                listenerId,
                audiobookId);
    }

    @Override
    public void denyAccount(UUID listenerId) {
        Instant now = databaseTime(identityClock.instant());
        String previous =
                jdbcTemplate.queryForObject(
                        "SELECT access_state FROM listener_identity WHERE listener_id = ? FOR UPDATE",
                        String.class,
                        listenerId);
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
                listenerId);
        if (!"ACTIVE".equals(previous)) {
            jdbcTemplate.update(
                    "UPDATE offline_access.authorization_generation"
                            + " SET generation = generation + 1, updated_at = ?"
                            + " WHERE listener_id = ?",
                    Timestamp.from(now),
                    listenerId);
        }
        jdbcTemplate.update(
                "UPDATE listener_identity SET access_state = 'DELETED',"
                        + " display_name = 'Deleted Listener', contact_email = NULL"
                        + " WHERE listener_id = ?",
                listenerId);
        jdbcTemplate.update(
                "UPDATE library.private_audiobook SET availability = 'DELETING',"
                        + " version = version + 1 WHERE listener_id = ? AND availability <> 'ERASED'",
                listenerId);
        tombstoneRestoredExternalIdentities(listenerId);
        jdbcTemplate.update("DELETE FROM external_identity_link WHERE listener_id = ?", listenerId);
        jdbcTemplate.update(
                "DELETE FROM spring_session WHERE principal_name = ?", listenerId.toString());
    }

    private void tombstoneRestoredExternalIdentities(UUID listenerId) {
        Instant now = databaseTime(identityClock.instant());
        List<ExternalIdentitySource> identities =
                jdbcTemplate.query(
                        "SELECT issuer, subject FROM external_identity_link WHERE listener_id = ?",
                        (resultSet, row) ->
                                new ExternalIdentitySource(
                                        resultSet.getString("issuer"),
                                        resultSet.getString("subject")),
                        listenerId);
        UUID requestId =
                jdbcTemplate.queryForObject(
                        "SELECT request_id FROM retention.deletion_tombstone"
                                + " WHERE scope = 'ACCOUNT' AND subject_digest = ?",
                        UUID.class,
                        retentionDigest.digest("listener", listenerId.toString()));
        for (ExternalIdentitySource identity : identities) {
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
    }

    @Override
    public void reissueAudiobook(Tombstone tombstone, UUID listenerId, UUID audiobookId) {
        UUID requestId = insertReplayRequest(tombstone, "AUDIOBOOK");
        for (AssetReference asset : audiobookAssets(listenerId, audiobookId)) {
            insertObligation(requestId, asset);
        }
        insertObligation(
                requestId,
                new AssetReference(
                        "RELATIONAL",
                        "RELATIONAL_PRIVATE_DATA",
                        "AUDIOBOOK\n" + listenerId + "\n" + audiobookId));
    }

    @Override
    public void reissueAccount(Tombstone tombstone, UUID listenerId) {
        UUID requestId = insertReplayRequest(tombstone, "ACCOUNT");
        for (AssetReference asset : accountAssets(listenerId)) {
            insertObligation(requestId, asset);
        }
        insertObligation(
                requestId,
                new AssetReference(
                        "RELATIONAL", "RELATIONAL_PRIVATE_DATA", "ACCOUNT\n" + listenerId));
    }

    private UUID insertReplayRequest(Tombstone tombstone, String scope) {
        Instant now = databaseTime(identityClock.instant());
        UUID requestId = identifierGenerator.generate();
        String operationKey =
                retentionDigest.digest("deletion-operation", "restore-replay:" + requestId);
        jdbcTemplate.update(
                """
                INSERT INTO retention.deletion_request (
                    request_id, scope, subject_digest, resource_digest, operation_key,
                    request_fingerprint, state, requested_at, quick_erasure_due_at,
                    live_erasure_due_at, provider_evidence_due_at, backup_expires_at,
                    evidence_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?, ?, ?, ?, ?)
                """,
                requestId,
                scope,
                tombstone.subjectDigest(),
                tombstone.resourceDigest(),
                operationKey,
                sha256("RESTORE_REPLAY\n" + tombstone.tombstoneId() + "\n" + requestId),
                Timestamp.from(now),
                Timestamp.from(now.plus(properties.quickErasureTarget())),
                Timestamp.from(now.plus(properties.liveErasureDeadline())),
                Timestamp.from(now.plus(properties.providerEvidenceDeadline())),
                Timestamp.from(now.plus(properties.backupExpiry())),
                Timestamp.from(now.plus(properties.evidenceRetention())));
        return requestId;
    }

    private List<AssetReference> audiobookAssets(UUID listenerId, UUID audiobookId) {
        return jdbcTemplate.query(
                """
                SELECT 'FINAL_ASSET' AS category, 'AUDIO_FINAL' AS asset_kind,
                       asset.manifest_object_key AS locator
                FROM library.audiobook_asset_version asset
                WHERE asset.listener_id = ? AND asset.audiobook_id = ?
                UNION ALL
                SELECT 'FINAL_ASSET', 'AUDIO_FINAL', part.object_key
                FROM library.final_asset_part part
                JOIN library.audiobook_asset_version asset
                  ON asset.asset_version_id = part.asset_version_id
                WHERE part.listener_id = ? AND asset.audiobook_id = ?
                UNION ALL
                SELECT 'WORKING_ASSET', 'NARRATION_PLAN', plan.working_asset_ref
                FROM narration.narration_plan plan
                JOIN library.private_audiobook audiobook
                  ON audiobook.conversion_id = plan.conversion_id
                WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                UNION ALL
                SELECT 'WORKING_ASSET', 'NARRATION_REVIEW', review.working_asset_ref
                FROM narration.narration_review_decision review
                JOIN library.private_audiobook audiobook
                  ON audiobook.conversion_id = review.conversion_id
                WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                UNION ALL
                SELECT 'WORKING_ASSET', 'AUDIO_WORKING', segment.spoken_text_ref
                FROM generation.speech_segment segment
                JOIN library.private_audiobook audiobook
                  ON audiobook.conversion_id = segment.conversion_id
                WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                UNION ALL
                SELECT 'WORKING_ASSET', 'AUDIO_WORKING', accepted.pcm_object_key
                FROM generation.accepted_segment accepted
                JOIN library.private_audiobook audiobook
                  ON audiobook.conversion_id = accepted.conversion_id
                WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                UNION ALL
                SELECT 'WORKING_ASSET', 'QUARANTINE_OBJECT', quarantine.object_id::text
                FROM admission.quarantine_object quarantine
                JOIN admission.source_publication publication
                  ON publication.submission_id = quarantine.submission_id
                JOIN workflow.audiobook_conversion conversion
                  ON conversion.source_publication_id = publication.source_publication_id
                JOIN library.private_audiobook audiobook
                  ON audiobook.conversion_id = conversion.conversion_id
                WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                UNION ALL
                SELECT 'PROVIDER', 'PROVIDER_EVIDENCE', evidence.operation_id
                FROM provider.operation_evidence evidence
                JOIN narration.generation_recipe recipe
                  ON recipe.recipe_id = evidence.generation_recipe_id
                JOIN library.private_audiobook audiobook
                  ON audiobook.conversion_id = recipe.conversion_id
                WHERE audiobook.listener_id = ? AND audiobook.audiobook_id = ?
                """,
                (resultSet, row) ->
                        new AssetReference(
                                resultSet.getString("category"),
                                resultSet.getString("asset_kind"),
                                resultSet.getString("locator")),
                listenerId,
                audiobookId,
                listenerId,
                audiobookId,
                listenerId,
                audiobookId,
                listenerId,
                audiobookId,
                listenerId,
                audiobookId,
                listenerId,
                audiobookId,
                listenerId,
                audiobookId,
                listenerId,
                audiobookId);
    }

    private List<AssetReference> accountAssets(UUID listenerId) {
        List<AssetReference> assets = new ArrayList<>();
        List<UUID> audiobookIds =
                jdbcTemplate.queryForList(
                        "SELECT audiobook_id FROM library.private_audiobook WHERE listener_id = ?",
                        UUID.class,
                        listenerId);
        for (UUID audiobookId : audiobookIds) {
            assets.addAll(audiobookAssets(listenerId, audiobookId));
        }
        assets.addAll(
                jdbcTemplate.query(
                        """
                        SELECT 'WORKING_ASSET' AS category, 'NARRATION_PLAN' AS asset_kind,
                               working_asset_ref AS locator
                        FROM narration.narration_plan
                        WHERE listener_id = ?
                        UNION ALL
                        SELECT 'WORKING_ASSET', 'NARRATION_REVIEW', working_asset_ref
                        FROM narration.narration_review_decision
                        WHERE listener_id = ?
                        UNION ALL
                        SELECT 'WORKING_ASSET', 'AUDIO_WORKING', spoken_text_ref
                        FROM generation.speech_segment
                        WHERE listener_id = ?
                        UNION ALL
                        SELECT 'WORKING_ASSET', 'AUDIO_WORKING', pcm_object_key
                        FROM generation.accepted_segment
                        WHERE listener_id = ?
                        UNION ALL
                        SELECT 'WORKING_ASSET', 'QUARANTINE_OBJECT', object_id::text
                        FROM admission.quarantine_object
                        WHERE listener_id = ?
                        UNION ALL
                        SELECT 'PROVIDER', 'PROVIDER_EVIDENCE', evidence.operation_id
                        FROM provider.operation_evidence evidence
                        JOIN narration.generation_recipe recipe
                          ON recipe.recipe_id = evidence.generation_recipe_id
                        WHERE recipe.listener_id = ?
                        """,
                        (resultSet, row) ->
                                new AssetReference(
                                        resultSet.getString("category"),
                                        resultSet.getString("asset_kind"),
                                        resultSet.getString("locator")),
                        listenerId,
                        listenerId,
                        listenerId,
                        listenerId,
                        listenerId,
                        listenerId));
        return assets;
    }

    private void insertObligation(UUID requestId, AssetReference asset) {
        Instant now = databaseTime(identityClock.instant());
        Instant dueAt =
                asset.category().equals("PROVIDER")
                        ? now.plus(properties.providerEvidenceDeadline())
                        : now.plus(properties.quickErasureTarget());
        Instant hardDueAt =
                asset.category().equals("PROVIDER")
                        ? now.plus(properties.providerEvidenceDeadline())
                        : now.plus(properties.liveErasureDeadline());
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
                asset.category(),
                asset.assetKind(),
                asset.locator(),
                retentionDigest.digest(
                        "erasure-locator", asset.assetKind() + "\n" + asset.locator()),
                Timestamp.from(databaseTime(dueAt)),
                Timestamp.from(databaseTime(hardDueAt)),
                Timestamp.from(now));
    }

    @Override
    public boolean hasIncompleteRequest(String subjectDigest, String resourceDigest) {
        Integer count;
        if (resourceDigest == null) {
            count =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM retention.deletion_request"
                                    + " WHERE subject_digest = ? AND scope = 'ACCOUNT'"
                                    + " AND state IN ('ACCEPTED', 'ERASING', 'LIVE_ERASED')",
                            Integer.class,
                            subjectDigest);
        } else {
            count =
                    jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM retention.deletion_request"
                                    + " WHERE subject_digest = ? AND resource_digest = ?"
                                    + " AND state IN ('ACCEPTED', 'ERASING', 'LIVE_ERASED')",
                            Integer.class,
                            subjectDigest,
                            resourceDigest);
        }
        return count != null && count > 0;
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

    private static Instant databaseTime(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private record ExternalIdentitySource(String issuer, String subject) {}

    private record AssetReference(String category, String assetKind, String locator) {}
}
