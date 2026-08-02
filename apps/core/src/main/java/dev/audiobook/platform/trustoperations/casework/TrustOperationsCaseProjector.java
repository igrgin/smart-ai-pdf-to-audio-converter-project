package dev.audiobook.platform.trustoperations.casework;

import dev.audiobook.platform.trustoperations.service.TrustOperationsService;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.CaseType;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.OpenCaseRequest;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.PrivilegedAction;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public final class TrustOperationsCaseProjector {

    private static final Set<PrivilegedAction> VIEW_REFERENCE =
            Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE);

    private final JdbcTemplate jdbcTemplate;
    private final TrustOperationsService trustOperationsService;
    private final Clock identityClock;

    public void projectAuthoritativeCases() {
        Instant now = identityClock.instant();
        projectFailedStages(now);
        projectExpiringAccess(now);
        projectEntitlementInterventions(now);
        projectVoiceAvailability(now);
        projectServiceIncidents(now);
        projectComplianceIncidents(now);
    }

    private void projectFailedStages(Instant now) {
        List<OpenCaseRequest> cases =
                jdbcTemplate
                        .query(
                                """
                                SELECT conversion_id, listener_id
                                FROM workflow.audiobook_conversion
                                WHERE state = 'FAILED'
                                """,
                                (resultSet, row) ->
                                        new Source(
                                                resultSet.getObject("conversion_id", UUID.class),
                                                resultSet.getObject("listener_id", UUID.class),
                                                "failed-stage:"
                                                        + resultSet.getObject(
                                                                "conversion_id", UUID.class)))
                        .stream()
                        .map(
                                source ->
                                        new OpenCaseRequest(
                                                CaseType.FAILED_STAGE,
                                                source.listenerId(),
                                                "AUDIOBOOK_CONVERSION",
                                                source.resourceId(),
                                                "CONVERSION_FAILED",
                                                "AUDIOBOOK_REMAINS_UNAVAILABLE",
                                                now.plusSeconds(4 * 3600),
                                                85,
                                                90,
                                                VIEW_REFERENCE,
                                                source.correlationId()))
                        .toList();
        projectAndReconcile("failed-stage:", cases, now);
    }

    private void projectExpiringAccess(Instant now) {
        List<OpenCaseRequest> cases =
                jdbcTemplate
                        .query(
                                """
                                SELECT g.grant_id, g.listener_id, c.resource_id, g.expires_at
                                FROM trust_operations.delegated_access_grant g
                                JOIN trust_operations.operations_case c ON c.case_id = g.case_id
                                LEFT JOIN trust_operations.delegated_access_revocation r ON r.grant_id = g.grant_id
                                WHERE r.grant_id IS NULL AND g.expires_at > ? AND g.expires_at <= ?
                                """,
                                (resultSet, row) ->
                                        new TimedSource(
                                                resultSet.getObject("resource_id", UUID.class),
                                                resultSet.getObject("listener_id", UUID.class),
                                                "expiring-access:"
                                                        + resultSet.getObject(
                                                                "grant_id", UUID.class),
                                                resultSet.getTimestamp("expires_at").toInstant()),
                                java.sql.Timestamp.from(now),
                                java.sql.Timestamp.from(now.plusSeconds(3600)))
                        .stream()
                        .map(
                                source ->
                                        new OpenCaseRequest(
                                                CaseType.EXPIRING_ACCESS,
                                                source.listenerId(),
                                                "DELEGATED_RESOURCE",
                                                source.resourceId(),
                                                "ACCESS_EXPIRING",
                                                "AUTHORIZED_SUPPORT_ACCESS_ENDS_SOON",
                                                source.occurredAt(),
                                                70,
                                                80,
                                                VIEW_REFERENCE,
                                                source.correlationId()))
                        .toList();
        projectAndReconcile("expiring-access:", cases, now);
    }

    private void projectEntitlementInterventions(Instant now) {
        List<OpenCaseRequest> cases =
                jdbcTemplate
                        .query(
                                """
                                SELECT event_id, listener_id, COALESCE(conversion_id, listener_id) AS resource_id, occurred_at
                                FROM entitlement_audit_event
                                WHERE decision = 'DENIED' AND listener_id IS NOT NULL AND occurred_at > ?
                                """,
                                (resultSet, row) ->
                                        new TimedSource(
                                                resultSet.getObject("resource_id", UUID.class),
                                                resultSet.getObject("listener_id", UUID.class),
                                                "entitlement-denied:"
                                                        + resultSet.getObject(
                                                                "event_id", UUID.class),
                                                resultSet.getTimestamp("occurred_at").toInstant()),
                                java.sql.Timestamp.from(now.minusSeconds(24 * 3600)))
                        .stream()
                        .map(
                                source ->
                                        new OpenCaseRequest(
                                                CaseType.ENTITLEMENT_INTERVENTION,
                                                source.listenerId(),
                                                "CONVERSION_ENTITLEMENT",
                                                source.resourceId(),
                                                "ENTITLEMENT_INTERVENTION_REQUIRED",
                                                "CONVERSION_CANNOT_START",
                                                source.occurredAt().plusSeconds(24 * 3600),
                                                65,
                                                70,
                                                VIEW_REFERENCE,
                                                source.correlationId()))
                        .toList();
        projectAndReconcile("entitlement-denied:", cases, now);
    }

    private void projectVoiceAvailability(Instant now) {
        List<OpenCaseRequest> cases =
                jdbcTemplate
                        .query(
                                """
                                SELECT voice_id, availability FROM narration.narrator_voice WHERE availability <> 'AVAILABLE'
                                """,
                                (resultSet, row) ->
                                        new Source(
                                                resultSet.getObject("voice_id", UUID.class),
                                                null,
                                                "voice-availability:"
                                                        + resultSet.getObject(
                                                                "voice_id", UUID.class)
                                                        + ":"
                                                        + resultSet.getString("availability")))
                        .stream()
                        .map(
                                source ->
                                        new OpenCaseRequest(
                                                CaseType.VOICE_AVAILABILITY,
                                                null,
                                                "NARRATOR_VOICE",
                                                source.resourceId(),
                                                "VOICE_NOT_AVAILABLE",
                                                "LISTENERS_CANNOT_SELECT_THIS_VOICE",
                                                now.plusSeconds(12 * 3600),
                                                55,
                                                60,
                                                VIEW_REFERENCE,
                                                source.correlationId()))
                        .toList();
        projectAndReconcile("voice-availability:", cases, now);
    }

    private void projectServiceIncidents(Instant now) {
        List<OpenCaseRequest> cases =
                jdbcTemplate
                        .query(
                                """
                                SELECT DISTINCT p.profile_id, c.conversion_id, c.listener_id
                                FROM narration.provider_capability_profile p
                                JOIN narration.generation_recipe r ON r.capability_profile_id = p.profile_id
                                JOIN workflow.audiobook_conversion c ON c.current_generation_recipe_id = r.recipe_id
                                WHERE c.state <> 'FINALIZED' AND (
                                    p.privacy_state = 'BLOCKED' OR p.region_state = 'BLOCKED'
                                    OR p.access_state = 'BLOCKED' OR p.quota_state = 'BLOCKED'
                                    OR p.evaluation_state = 'BLOCKED'
                                )
                                """,
                                (resultSet, row) ->
                                        new Source(
                                                resultSet.getObject("conversion_id", UUID.class),
                                                resultSet.getObject("listener_id", UUID.class),
                                                "service-incident:"
                                                        + resultSet.getObject(
                                                                "profile_id", UUID.class)
                                                        + ":"
                                                        + resultSet.getObject(
                                                                "conversion_id", UUID.class)))
                        .stream()
                        .map(
                                source ->
                                        new OpenCaseRequest(
                                                CaseType.SERVICE_INCIDENT,
                                                source.listenerId(),
                                                "AUDIOBOOK_CONVERSION",
                                                source.resourceId(),
                                                "PROVIDER_CAPABILITY_BLOCKED",
                                                "CONVERSION_STAGES_MAY_BE_UNAVAILABLE",
                                                now.plusSeconds(900),
                                                100,
                                                100,
                                                VIEW_REFERENCE,
                                                source.correlationId()))
                        .toList();
        projectAndReconcile("service-incident:", cases, now);
    }

    private void projectComplianceIncidents(Instant now) {
        List<OpenCaseRequest> cases =
                jdbcTemplate
                        .query(
                                """
                                SELECT incident_id, incident_code, deadline
                                FROM retention.compliance_incident
                                WHERE resolved_at IS NULL
                                """,
                                (resultSet, row) ->
                                        new ComplianceIncidentSource(
                                                resultSet.getObject("incident_id", UUID.class),
                                                resultSet.getString("incident_code"),
                                                resultSet.getTimestamp("deadline").toInstant()))
                        .stream()
                        .map(
                                source ->
                                        new OpenCaseRequest(
                                                CaseType.COMPLIANCE_INCIDENT,
                                                null,
                                                "ERASURE_REQUEST",
                                                source.incidentId(),
                                                source.code(),
                                                "ERASURE_EVIDENCE_REQUIRES_URGENT_REVIEW",
                                                source.deadline(),
                                                100,
                                                100,
                                                VIEW_REFERENCE,
                                                "compliance-incident:" + source.incidentId()))
                        .toList();
        projectAndReconcile("compliance-incident:", cases, now);
    }

    private void projectAndReconcile(
            String correlationPrefix, List<OpenCaseRequest> cases, Instant now) {
        Set<String> activeCorrelations =
                cases.stream()
                        .map(OpenCaseRequest::correlationId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        cases.forEach(
                request -> {
                    publish(request);
                    jdbcTemplate.update(
                            """
                            UPDATE trust_operations.operations_case
                            SET resolved_at = NULL, deadline = ?
                            WHERE correlation_id = ? AND resolved_at IS NOT NULL
                            """,
                            java.sql.Timestamp.from(request.deadline()),
                            request.correlationId());
                });
        jdbcTemplate
                .queryForList(
                        """
                        SELECT correlation_id
                        FROM trust_operations.operations_case
                        WHERE correlation_id LIKE ? AND resolved_at IS NULL
                        """,
                        String.class,
                        correlationPrefix + "%")
                .stream()
                .filter(correlation -> !activeCorrelations.contains(correlation))
                .forEach(
                        correlation ->
                                jdbcTemplate.update(
                                        "UPDATE trust_operations.operations_case SET resolved_at ="
                                                + " ? WHERE correlation_id = ?",
                                        java.sql.Timestamp.from(now),
                                        correlation));
    }

    private void publish(OpenCaseRequest request) {
        Integer existing =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM trust_operations.operations_case WHERE correlation_id"
                                + " = ?",
                        Integer.class,
                        request.correlationId());
        if (existing != null && existing == 0) {
            try {
                trustOperationsService.openCase(request);
            } catch (DuplicateKeyException concurrentProjection) {
                // Another queue reader projected the same authoritative source first.
            }
        }
    }

    private record Source(UUID resourceId, UUID listenerId, String correlationId) {}

    private record TimedSource(
            UUID resourceId, UUID listenerId, String correlationId, Instant occurredAt) {}

    private record ComplianceIncidentSource(UUID incidentId, String code, Instant deadline) {}
}
