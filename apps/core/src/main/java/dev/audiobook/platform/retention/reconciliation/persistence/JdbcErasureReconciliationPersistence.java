package dev.audiobook.platform.retention.reconciliation.persistence;

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
public class JdbcErasureReconciliationPersistence implements ErasureReconciliationPersistence {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final RetentionProperties properties;

    @Override
    public void purgeExpiredEvidence(Instant now) {
        jdbcTemplate.update(
                "DELETE FROM retention.erasure_evidence WHERE expires_at <= ?",
                Timestamp.from(now));
    }

    @Override
    public void resolveCompletedRequestIncidents(Instant now) {
        jdbcTemplate.update(
                """
                UPDATE retention.compliance_incident incident
                SET resolved_at = ?
                FROM retention.deletion_request request
                WHERE incident.request_id = request.request_id
                  AND request.state = 'COMPLETED'
                  AND incident.resolved_at IS NULL
                """,
                Timestamp.from(now));
    }

    @Override
    public List<RequestProgress> incompleteRequests() {
        return jdbcTemplate.query(
                """
                SELECT request.request_id, request.quick_erasure_due_at,
                       request.live_erasure_due_at, request.provider_evidence_due_at,
                       count(obligation.obligation_id) FILTER (
                           WHERE obligation.category <> 'PROVIDER'
                       ) AS live_total,
                       count(obligation.obligation_id) FILTER (
                           WHERE obligation.category <> 'PROVIDER'
                             AND obligation.state = 'COMPLETED'
                       ) AS live_completed,
                       count(obligation.obligation_id) FILTER (
                           WHERE obligation.category = 'PROVIDER'
                             AND obligation.state <> 'COMPLETED'
                       ) AS provider_incomplete,
                       count(obligation.obligation_id) FILTER (
                           WHERE obligation.state = 'FAILED'
                             AND obligation.attempt_count >= ?
                       ) AS exhausted
                FROM retention.deletion_request request
                JOIN retention.erasure_obligation obligation
                  ON obligation.request_id = request.request_id
                WHERE request.state <> 'COMPLETED'
                GROUP BY request.request_id
                """,
                (resultSet, row) ->
                        new RequestProgress(
                                resultSet.getObject("request_id", UUID.class),
                                resultSet.getTimestamp("quick_erasure_due_at").toInstant(),
                                resultSet.getTimestamp("live_erasure_due_at").toInstant(),
                                resultSet.getTimestamp("provider_evidence_due_at").toInstant(),
                                resultSet.getInt("live_total"),
                                resultSet.getInt("live_completed"),
                                resultSet.getInt("provider_incomplete"),
                                resultSet.getInt("exhausted")),
                properties.maximumAttempts());
    }

    @Override
    public int createIncident(
            UUID requestId, String code, Instant detectedAt, Instant deadline) {
        return jdbcTemplate.update(
                """
                INSERT INTO retention.compliance_incident (
                    incident_id, request_id, incident_code, detected_at, deadline
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (request_id, incident_code) DO UPDATE SET resolved_at = NULL
                """,
                identifierGenerator.generate(),
                requestId,
                code,
                Timestamp.from(detectedAt),
                Timestamp.from(deadline));
    }

    @Override
    public void resolveIncidents(UUID requestId, Instant now) {
        jdbcTemplate.update(
                "UPDATE retention.compliance_incident SET resolved_at = ?"
                        + " WHERE request_id = ? AND resolved_at IS NULL",
                Timestamp.from(now),
                requestId);
    }
}
