package dev.audiobook.platform.retention.erasure.persistence;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.retention.RetentionProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JdbcErasureWorkerPersistence implements ErasureWorkerPersistence {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final RetentionProperties properties;

    @Override
    public List<Obligation> claimPending() {
        return jdbcTemplate.query(
                """
                SELECT obligation.obligation_id, obligation.request_id,
                       obligation.asset_kind, obligation.locator, obligation.attempt_count
                FROM retention.erasure_obligation obligation
                WHERE obligation.state IN ('PENDING', 'FAILED')
                  AND obligation.attempt_count < ?
                  AND (
                      obligation.category <> 'RELATIONAL'
                      OR NOT EXISTS (
                          SELECT 1 FROM retention.erasure_obligation dependency
                          WHERE dependency.request_id = obligation.request_id
                            AND dependency.category IN ('WORKING_ASSET', 'FINAL_ASSET', 'PROVIDER')
                            AND dependency.state <> 'COMPLETED'
                      )
                  )
                ORDER BY CASE obligation.category
                             WHEN 'WORKING_ASSET' THEN 1
                             WHEN 'FINAL_ASSET' THEN 2
                             WHEN 'PROVIDER' THEN 3
                             ELSE 4
                         END,
                         obligation.created_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                JdbcErasureWorkerPersistence::obligation,
                properties.maximumAttempts(),
                properties.workerBatchSize());
    }

    @Override
    public List<Obligation> claimEligibleRelational() {
        return jdbcTemplate.query(
                """
                SELECT obligation.obligation_id, obligation.request_id,
                       obligation.asset_kind, obligation.locator, obligation.attempt_count
                FROM retention.erasure_obligation obligation
                WHERE obligation.state = 'PENDING'
                  AND obligation.category = 'RELATIONAL'
                  AND NOT EXISTS (
                      SELECT 1 FROM retention.erasure_obligation dependency
                      WHERE dependency.request_id = obligation.request_id
                        AND dependency.category IN ('WORKING_ASSET', 'FINAL_ASSET', 'PROVIDER')
                        AND dependency.state <> 'COMPLETED'
                  )
                ORDER BY obligation.created_at
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                JdbcErasureWorkerPersistence::obligation,
                properties.workerBatchSize());
    }

    @Override
    public void markErasing(Obligation obligation) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.erasure_request_id', ?, true)",
                String.class,
                obligation.requestId().toString());
        jdbcTemplate.update(
                "UPDATE retention.deletion_request SET state = 'ERASING'"
                        + " WHERE request_id = ? AND state IN ('ACCEPTED', 'FAILED')",
                obligation.requestId());
        jdbcTemplate.update(
                """
                UPDATE retention.erasure_obligation
                SET state = 'ERASING', attempt_count = attempt_count + 1, failure_code = NULL
                WHERE obligation_id = ?
                """,
                obligation.obligationId());
    }

    @Override
    public void complete(Obligation obligation, String evidenceCode) {
        jdbcTemplate.queryForObject(
                "SELECT retention.complete_erasure_obligation(?::uuid, ?::varchar)",
                Object.class,
                obligation.obligationId(),
                evidenceCode);
    }

    @Override
    public void fail(Obligation obligation, String failureCode) {
        jdbcTemplate.update(
                "UPDATE retention.erasure_obligation SET state = 'FAILED', failure_code = ?"
                        + " WHERE obligation_id = ?",
                failureCode,
                obligation.obligationId());
    }

    @Override
    public void failRequest(UUID requestId, String failureCode) {
        jdbcTemplate.update(
                "UPDATE retention.deletion_request SET state = 'FAILED', failure_code = ?"
                        + " WHERE request_id = ?",
                failureCode,
                requestId);
    }

    @Override
    public boolean hasQualifiedProviderEvidence(String operationId) {
        Integer evidence =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM provider.operation_evidence operation
                        JOIN narration.provider_capability_profile profile
                          ON profile.profile_id = operation.capability_profile_id
                        WHERE operation.operation_id = ?
                          AND profile.privacy_state = 'QUALIFIED'
                          AND profile.data_policy_version IS NOT NULL
                          AND profile.erasure_strategy = 'NON_RETENTION_CONTRACT'
                          AND profile.erasure_evidence_type = 'DATA_POLICY_VERSION'
                        """,
                        Integer.class,
                        operationId);
        return evidence != null && evidence == 1;
    }

    @Override
    public void eraseRelational(UUID requestId, String locator) {
        String[] coordinates = locator.split("\\n");
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.erasure_request_id', ?, true)",
                String.class,
                requestId.toString());
        if (coordinates.length == 3 && coordinates[0].equals("AUDIOBOOK")) {
            UUID listenerId = UUID.fromString(coordinates[1]);
            jdbcTemplate.queryForObject(
                    "SELECT retention.erase_audiobook_private_data(?, ?)",
                    Object.class,
                    listenerId,
                    UUID.fromString(coordinates[2]));
            return;
        }
        if (coordinates.length == 2 && coordinates[0].equals("ACCOUNT")) {
            jdbcTemplate.queryForObject(
                    "SELECT retention.erase_account_private_data(?)",
                    Object.class,
                    UUID.fromString(coordinates[1]));
            return;
        }
        throw new IllegalArgumentException("Relational erasure coordinates are invalid");
    }

    @Override
    public void refreshRequest(UUID requestId, Instant completedAt) {
        int incompleteLive =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM retention.erasure_obligation"
                                + " WHERE request_id = ? AND category <> 'PROVIDER'"
                                + " AND state <> 'COMPLETED'",
                        Integer.class,
                        requestId);
        int incompleteProvider =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM retention.erasure_obligation"
                                + " WHERE request_id = ? AND category = 'PROVIDER'"
                                + " AND state <> 'COMPLETED'",
                        Integer.class,
                        requestId);
        if (incompleteLive == 0) {
            jdbcTemplate.update(
                    "UPDATE retention.deletion_request SET state = 'LIVE_ERASED',"
                            + " live_erased_at = COALESCE(live_erased_at, ?) WHERE request_id = ?",
                    Timestamp.from(completedAt),
                    requestId);
        }
        if (incompleteLive != 0 || incompleteProvider != 0) {
            return;
        }
        jdbcTemplate.update(
                """
                UPDATE retention.deletion_request
                SET state = 'COMPLETED', provider_evidenced_at = COALESCE(provider_evidenced_at, ?),
                    completed_at = COALESCE(completed_at, ?), failure_code = NULL
                WHERE request_id = ?
                """,
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                requestId);
        int completed =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM retention.erasure_obligation"
                                + " WHERE request_id = ? AND state = 'COMPLETED'",
                        Integer.class,
                        requestId);
        Instant evidenceExpiry =
                jdbcTemplate.queryForObject(
                                "SELECT evidence_expires_at FROM retention.deletion_request"
                                        + " WHERE request_id = ?",
                                Timestamp.class,
                                requestId)
                        .toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO retention.erasure_evidence (
                    evidence_id, request_id, evidence_type, evidence_code,
                    item_count, recorded_at, expires_at
                ) VALUES (?, ?, 'BOUNDED_ERASURE', 'LIVE_AND_PROVIDER_ERASURE_COMPLETED', ?, ?, ?)
                ON CONFLICT (request_id, evidence_type) DO NOTHING
                """,
                identifierGenerator.generate(),
                requestId,
                completed,
                Timestamp.from(completedAt),
                Timestamp.from(evidenceExpiry));
    }

    @Override
    public void createIncident(
            UUID requestId, String code, Instant detectedAt, Instant deadline) {
        jdbcTemplate.update(
                """
                INSERT INTO retention.compliance_incident (
                    incident_id, request_id, incident_code, detected_at, deadline
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (request_id, incident_code) DO NOTHING
                """,
                identifierGenerator.generate(),
                requestId,
                code,
                Timestamp.from(detectedAt),
                Timestamp.from(deadline));
    }

    private static Obligation obligation(java.sql.ResultSet resultSet, int row)
            throws java.sql.SQLException {
        return new Obligation(
                resultSet.getObject("obligation_id", UUID.class),
                resultSet.getObject("request_id", UUID.class),
                resultSet.getString("asset_kind"),
                resultSet.getString("locator"),
                resultSet.getInt("attempt_count"));
    }
}
