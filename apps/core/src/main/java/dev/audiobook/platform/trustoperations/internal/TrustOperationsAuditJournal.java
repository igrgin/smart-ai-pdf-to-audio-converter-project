package dev.audiobook.platform.trustoperations.internal;

import dev.audiobook.platform.trustoperations.TrustOperationsService;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.trustoperations.TrustOperationsService.AuditEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class TrustOperationsAuditJournal {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;

    List<AuditEvent> events(UUID caseId) {
        return jdbcTemplate.query(
                """
                SELECT event_id, actor_reference, authority, target_reference, purpose_code,
                       policy_code, action_code, outcome, occurred_at, correlation_id,
                       notification_id, review_obligation, appeal_obligation
                FROM trust_operations.privileged_action_audit
                WHERE case_id = ?
                ORDER BY occurred_at, event_id
                """,
                (resultSet, row) -> event(resultSet),
                caseId);
    }

    AuditReplay findByCorrelation(String correlationId) {
        List<AuditReplay> rows = jdbcTemplate.query(
                """
                SELECT grant_id, actor_id, event_id, actor_reference, authority, target_reference,
                       purpose_code, policy_code, action_code, outcome, occurred_at,
                       correlation_id, notification_id, review_obligation, appeal_obligation
                FROM trust_operations.privileged_action_audit
                WHERE correlation_id = ?
                """,
                (resultSet, row) -> new AuditReplay(
                        resultSet.getObject("grant_id", UUID.class),
                        resultSet.getObject("actor_id", UUID.class),
                        event(resultSet)),
                correlationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void notifyListener(NotificationCommand command) {
        jdbcTemplate.update(
                """
                INSERT INTO trust_operations.listener_notification (
                    notification_id, listener_id, case_id, grant_id, event_type, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                command.notificationId(),
                command.listenerId(),
                command.caseId(),
                command.grantId(),
                command.eventType(),
                Timestamp.from(command.createdAt()));
    }

    AuditEvent append(AuditCommand command) {
        UUID eventId = identifierGenerator.generate();
        jdbcTemplate.update(
                """
                INSERT INTO trust_operations.privileged_action_audit (
                    event_id, case_id, grant_id, actor_id, actor_reference, authority,
                    target_reference, purpose_code, policy_code, action_code, outcome,
                    occurred_at, correlation_id, notification_id, review_obligation,
                    appeal_obligation
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                command.caseId(),
                command.grantId(),
                command.actorId(),
                command.actorReference(),
                command.authority(),
                command.targetReference(),
                command.purposeCode(),
                command.policyCode(),
                command.actionCode(),
                command.outcome(),
                Timestamp.from(command.occurredAt()),
                command.correlationId(),
                command.notificationId(),
                command.reviewObligation(),
                command.appealObligation());
        return new AuditEvent(
                eventId,
                command.actorReference(),
                command.authority(),
                command.targetReference(),
                command.purposeCode(),
                command.policyCode(),
                command.actionCode(),
                command.outcome(),
                command.occurredAt(),
                command.correlationId(),
                command.notificationId(),
                command.reviewObligation(),
                command.appealObligation());
    }

    private static AuditEvent event(ResultSet resultSet) throws SQLException {
        return new AuditEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("actor_reference", UUID.class),
                resultSet.getString("authority"),
                resultSet.getObject("target_reference", UUID.class),
                resultSet.getString("purpose_code"),
                resultSet.getString("policy_code"),
                resultSet.getString("action_code"),
                resultSet.getString("outcome"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getString("correlation_id"),
                resultSet.getObject("notification_id", UUID.class),
                resultSet.getString("review_obligation"),
                resultSet.getString("appeal_obligation"));
    }

    record NotificationCommand(
            UUID notificationId,
            UUID listenerId,
            UUID caseId,
            UUID grantId,
            String eventType,
            Instant createdAt) {
    }

    record AuditCommand(
            UUID caseId,
            UUID grantId,
            UUID actorId,
            UUID actorReference,
            String authority,
            UUID targetReference,
            String purposeCode,
            String policyCode,
            String actionCode,
            String outcome,
            Instant occurredAt,
            String correlationId,
            UUID notificationId,
            String reviewObligation,
            String appealObligation) {
    }

    record AuditReplay(UUID grantId, UUID actorId, AuditEvent event) {
    }
}
