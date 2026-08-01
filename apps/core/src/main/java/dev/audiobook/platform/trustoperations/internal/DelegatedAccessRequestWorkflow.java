package dev.audiobook.platform.trustoperations.internal;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.trustoperations.TrustOperationsProperties;
import dev.audiobook.platform.trustoperations.TrustOperationsService;
import dev.audiobook.platform.trustoperations.TrustOperationsService.ApproveDelegatedAccessCommand;
import dev.audiobook.platform.trustoperations.TrustOperationsService.DelegatedAccessGrant;
import dev.audiobook.platform.trustoperations.TrustOperationsService.PrivilegedAction;
import dev.audiobook.platform.trustoperations.TrustOperationsService.StaffContext;
import dev.audiobook.platform.trustoperations.TrustOperationsService.StaffRole;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class DelegatedAccessRequestWorkflow {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final TrustOperationsService trustOperationsService;
    private final TrustOperationsProperties properties;
    private final Clock identityClock;

    @Transactional
    PendingAccessRequest request(
            StaffContext staff,
            UUID caseId,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant expiresAt,
            String operationKey) {
        TrustOperationsPolicy.requireOperationKey(operationKey);
        PendingAccessRequest replay = byOperation(operationKey);
        if (replay != null) {
            if (!replay.caseId().equals(caseId)
                    || !replay.staffId().equals(staff.staffId())
                    || !replay.purposeCode().equals(purposeCode)
                    || !replay.allowedActions().equals(allowedActions)
                    || !replay.expiresAt().equals(expiresAt)) {
                throw new TrustOperationsConflictException();
            }
            return replay;
        }
        List<CaseRequestContext> cases = jdbcTemplate.query(
                """
                SELECT c.case_id, c.listener_id, c.required_role, c.opaque_resource_reference,
                       c.resource_kind, c.restriction_code, c.consequence_code, c.deadline,
                       c.allowed_actions, i.display_name
                FROM trust_operations.operations_case c
                JOIN public.listener_identity i ON i.listener_id = ?
                WHERE c.case_id = ? AND c.listener_id IS NOT NULL AND c.resolved_at IS NULL
                """,
                (resultSet, row) -> new CaseRequestContext(
                        resultSet.getObject("listener_id", UUID.class),
                        StaffRole.valueOf(resultSet.getString("required_role")),
                        resultSet.getObject("opaque_resource_reference", UUID.class),
                        resultSet.getString("resource_kind"),
                        resultSet.getString("restriction_code"),
                        resultSet.getString("consequence_code"),
                        resultSet.getTimestamp("deadline").toInstant(),
                        actions(resultSet.getArray("allowed_actions")),
                        resultSet.getString("display_name")),
                staff.staffId(),
                caseId);
        if (cases.isEmpty()) {
            throw new TrustOperationsAccessDeniedException();
        }
        CaseRequestContext context = cases.getFirst();
        Instant now = identityClock.instant();
        if (!staff.roles().contains(context.requiredRole())
                || purposeCode == null || purposeCode.isBlank()
                || allowedActions == null || allowedActions.isEmpty()
                || !context.caseActions().containsAll(allowedActions)
                || expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plus(properties.delegatedAccessMaximumDuration()))) {
            throw new IllegalArgumentException("Invalid delegated access request");
        }
        UUID requestId = identifierGenerator.generate();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO trust_operations.delegated_access_request (
                        request_id, case_id, listener_id, staff_id, staff_display_name,
                        purpose_code, allowed_actions, expires_at, operation_key, requested_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, requestId);
            statement.setObject(2, caseId);
            statement.setObject(3, context.listenerId());
            statement.setObject(4, staff.staffId());
            statement.setString(5, context.staffDisplayName());
            statement.setString(6, purposeCode);
            statement.setArray(7, connection.createArrayOf(
                    "varchar", allowedActions.stream().map(Enum::name).sorted().toArray(String[]::new)));
            statement.setTimestamp(8, Timestamp.from(expiresAt));
            statement.setString(9, operationKey);
            statement.setTimestamp(10, Timestamp.from(now));
            return statement;
        });
        return new PendingAccessRequest(
                requestId, caseId, context.listenerId(), staff.staffId(), context.staffDisplayName(),
                context.opaqueResourceReference(), context.resourceKind(), context.restrictionCode(),
                context.consequenceCode(), context.deadline(), purposeCode, allowedActions, expiresAt, now);
    }

    @Transactional
    DelegatedAccessGrant approve(UUID listenerId, UUID requestId, String operationKey) {
        PendingAccessRequest request = find(requestId);
        if (request == null || !request.listenerId().equals(listenerId)) {
            throw new TrustOperationsAccessDeniedException();
        }
        List<String> decisions = jdbcTemplate.queryForList(
                "SELECT operation_key FROM trust_operations.delegated_access_request_decision WHERE request_id = ?",
                String.class,
                requestId);
        if (!decisions.isEmpty() && !decisions.getFirst().equals(operationKey)) {
            throw new TrustOperationsPreconditionException();
        }
        DelegatedAccessGrant grant = trustOperationsService.approveDelegatedAccess(
                new ApproveDelegatedAccessCommand(
                        listenerId, request.caseId(), request.staffId(), request.purposeCode(),
                        request.allowedActions(), request.expiresAt(), operationKey));
        if (decisions.isEmpty()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO trust_operations.delegated_access_request_decision (
                        request_id, grant_id, operation_key, decided_at
                    ) VALUES (?, ?, ?, ?)
                    """,
                    requestId, grant.grantId(), operationKey, Timestamp.from(identityClock.instant()));
        }
        return grant;
    }

    List<PendingAccessRequest> pending(UUID listenerId) {
        return jdbcTemplate.query(
                requestSelect() + """
                 WHERE r.listener_id = ? AND d.request_id IS NULL
                 ORDER BY r.requested_at DESC, r.request_id
                """,
                (resultSet, row) -> pending(resultSet),
                listenerId);
    }

    private PendingAccessRequest find(UUID requestId) {
        List<PendingAccessRequest> rows = jdbcTemplate.query(
                requestSelect() + " WHERE r.request_id = ?",
                (resultSet, row) -> pending(resultSet),
                requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PendingAccessRequest byOperation(String operationKey) {
        List<PendingAccessRequest> rows = jdbcTemplate.query(
                requestSelect() + " WHERE r.operation_key = ?",
                (resultSet, row) -> pending(resultSet),
                operationKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String requestSelect() {
        return """
                SELECT r.request_id, r.case_id, r.listener_id, r.staff_id, r.staff_display_name,
                       c.opaque_resource_reference, c.resource_kind, c.restriction_code,
                       c.consequence_code, c.deadline, r.purpose_code, r.allowed_actions,
                       r.expires_at, r.requested_at
                FROM trust_operations.delegated_access_request r
                JOIN trust_operations.operations_case c ON c.case_id = r.case_id
                LEFT JOIN trust_operations.delegated_access_request_decision d ON d.request_id = r.request_id
                """;
    }

    private static PendingAccessRequest pending(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new PendingAccessRequest(
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("case_id", UUID.class),
                resultSet.getObject("listener_id", UUID.class),
                resultSet.getObject("staff_id", UUID.class),
                resultSet.getString("staff_display_name"),
                resultSet.getObject("opaque_resource_reference", UUID.class),
                resultSet.getString("resource_kind"),
                resultSet.getString("restriction_code"),
                resultSet.getString("consequence_code"),
                resultSet.getTimestamp("deadline").toInstant(),
                resultSet.getString("purpose_code"),
                actions(resultSet.getArray("allowed_actions")),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("requested_at").toInstant());
    }

    private static Set<PrivilegedAction> actions(Array array) throws java.sql.SQLException {
        return Arrays.stream((Object[]) array.getArray())
                .map(Object::toString)
                .map(PrivilegedAction::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    record PendingAccessRequest(
            UUID requestId,
            UUID caseId,
            @com.fasterxml.jackson.annotation.JsonIgnore
            UUID listenerId,
            UUID staffId,
            String staffDisplayName,
            UUID opaqueResourceReference,
            String resourceKind,
            String restrictionCode,
            String consequenceCode,
            Instant deadline,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant expiresAt,
            Instant requestedAt) {
    }

    private record CaseRequestContext(
            UUID listenerId,
            StaffRole requiredRole,
            UUID opaqueResourceReference,
            String resourceKind,
            String restrictionCode,
            String consequenceCode,
            Instant deadline,
            Set<PrivilegedAction> caseActions,
            String staffDisplayName) {
    }
}
