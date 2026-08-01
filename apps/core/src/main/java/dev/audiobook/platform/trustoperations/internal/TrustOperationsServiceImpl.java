package dev.audiobook.platform.trustoperations.internal;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.trustoperations.TrustOperationsService;
import dev.audiobook.platform.trustoperations.internal.TrustOperationsAuditJournal.AuditCommand;
import dev.audiobook.platform.trustoperations.internal.TrustOperationsAuditJournal.AuditReplay;
import dev.audiobook.platform.trustoperations.internal.TrustOperationsAuditJournal.NotificationCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TrustOperationsServiceImpl implements TrustOperationsService {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final Clock identityClock;
    private final TrustOperationsPolicy policy;
    private final TrustOperationsAuditJournal auditJournal;

    @Override
    @Transactional
    public OperationsCase openCase(OpenCaseRequest request) {
        policy.requireValidCase(request);
        CaseRow replay = findCaseByCorrelation(request.correlationId());
        if (replay != null) {
            if (!replay.matches(request)) {
                throw new TrustOperationsConflictException();
            }
            return operationsCase(replay);
        }
        UUID caseId = identifierGenerator.generate();
        UUID opaqueResourceReference = identifierGenerator.generate();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO trust_operations.operations_case (
                        case_id, case_type, required_role, listener_id, resource_kind, resource_id,
                        opaque_resource_reference, restriction_code, consequence_code, deadline,
                        safety_priority, urgency, allowed_actions, correlation_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, caseId);
            statement.setString(2, request.type().name());
            statement.setString(3, request.type().requiredRole().name());
            statement.setObject(4, request.listenerId());
            statement.setString(5, request.resourceKind());
            statement.setObject(6, request.resourceId());
            statement.setObject(7, opaqueResourceReference);
            statement.setString(8, request.restrictionCode());
            statement.setString(9, request.consequenceCode());
            statement.setTimestamp(10, Timestamp.from(request.deadline()));
            statement.setInt(11, request.safetyPriority());
            statement.setInt(12, request.urgency());
            statement.setArray(13, actionArray(connection, request.allowedActions()));
            statement.setString(14, request.correlationId());
            return statement;
        });
        return new OperationsCase(caseId, request.type(), opaqueResourceReference, request.resourceKind(),
                request.restrictionCode(), request.consequenceCode(), request.deadline(),
                request.safetyPriority(), request.urgency(), request.allowedActions());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OperationsCase> actionQueue(Set<StaffRole> roles) {
        Set<StaffRole> safeRoles = roles == null || roles.isEmpty()
                ? EnumSet.noneOf(StaffRole.class)
                : EnumSet.copyOf(roles);
        if (safeRoles.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement("""
                    SELECT case_id, case_type, opaque_resource_reference, resource_kind,
                           restriction_code, consequence_code, deadline, safety_priority,
                           urgency, allowed_actions
                    FROM trust_operations.operations_case
                    WHERE required_role = ANY (?) AND resolved_at IS NULL
                    ORDER BY safety_priority DESC, deadline ASC, urgency DESC, case_id
                    """);
            statement.setArray(1, connection.createArrayOf(
                    "varchar", safeRoles.stream().map(Enum::name).sorted().toArray(String[]::new)));
            return statement;
        }, (resultSet, row) -> new OperationsCase(
                resultSet.getObject("case_id", UUID.class),
                CaseType.valueOf(resultSet.getString("case_type")),
                resultSet.getObject("opaque_resource_reference", UUID.class),
                resultSet.getString("resource_kind"),
                resultSet.getString("restriction_code"),
                resultSet.getString("consequence_code"),
                resultSet.getTimestamp("deadline").toInstant(),
                resultSet.getInt("safety_priority"),
                resultSet.getInt("urgency"),
                actions(resultSet.getArray("allowed_actions"))));
    }

    @Override
    @Transactional(readOnly = true)
    public CaseDetails caseDetails(StaffContext staff, UUID caseId) {
        Objects.requireNonNull(staff, "staff");
        CaseRow operationsCase = findCase(caseId);
        if (!staff.roles().contains(operationsCase.type().requiredRole())) {
            throw new TrustOperationsAccessDeniedException();
        }
        return new CaseDetails(
                operationsCase.caseId(),
                operationsCase.type(),
                operationsCase.opaqueResourceReference(),
                operationsCase.resourceKind(),
                operationsCase.restrictionCode(),
                operationsCase.consequenceCode(),
                operationsCase.deadline(),
                operationsCase.allowedActions(),
                auditJournal.events(caseId));
    }

    @Override
    @Transactional
    public DelegatedAccessGrant approveDelegatedAccess(ApproveDelegatedAccessCommand command) {
        Objects.requireNonNull(command, "command");
        String requestFingerprint = fingerprint(
                command.listenerId(),
                command.caseId(),
                command.staffId(),
                command.purposeCode(),
                command.allowedActions().stream().map(Enum::name).sorted().toList(),
                command.expiresAt());
        GrantRow replay = findGrantByOperation(command.operationKey());
        if (replay != null) {
            if (!requestFingerprint.equals(replay.requestFingerprint())) {
                throw new TrustOperationsConflictException();
            }
            return grant(replay, false);
        }

        CaseRow operationsCase = findCase(command.caseId());
        Instant now = identityClock.instant();
        policy.requireDelegatedApproval(
                command, operationsCase.listenerId(), operationsCase.allowedActions(), now);
        UUID grantId = identifierGenerator.generate();
        UUID notificationId = identifierGenerator.generate();
        UUID actorReference = identifierGenerator.generate();

        auditJournal.notifyListener(new NotificationCommand(
                notificationId, command.listenerId(), command.caseId(), grantId,
                "DELEGATED_ACCESS_APPROVED", now));
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO trust_operations.delegated_access_grant (
                        grant_id, case_id, listener_id, staff_id, purpose_code, allowed_actions,
                        valid_from, expires_at, operation_key, request_fingerprint, notification_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, grantId);
            statement.setObject(2, command.caseId());
            statement.setObject(3, command.listenerId());
            statement.setObject(4, command.staffId());
            statement.setString(5, command.purposeCode());
            statement.setArray(6, actionArray(connection, command.allowedActions()));
            statement.setTimestamp(7, Timestamp.from(now));
            statement.setTimestamp(8, Timestamp.from(command.expiresAt()));
            statement.setString(9, command.operationKey());
            statement.setString(10, requestFingerprint);
            statement.setObject(11, notificationId);
            return statement;
        });
        auditJournal.append(new AuditCommand(
                command.caseId(),
                grantId,
                command.listenerId(),
                actorReference,
                "LISTENER_APPROVAL",
                operationsCase.opaqueResourceReference(),
                command.purposeCode(),
                "DELEGATED_SUPPORT_ACCESS_V1",
                "GRANT_DELEGATED_ACCESS",
                "GRANTED",
                now,
                command.operationKey(),
                notificationId,
                null,
                "LISTENER_REVOCATION_AVAILABLE"));
        return new DelegatedAccessGrant(
                grantId,
                command.caseId(),
                command.staffId(),
                command.purposeCode(),
                command.allowedActions(),
                now,
                command.expiresAt(),
                false,
                0,
                true);
    }

    @Override
    @Transactional
    public DelegatedAccessGrant revokeDelegatedAccess(RevokeDelegatedAccessCommand command) {
        Objects.requireNonNull(command, "command");
        GrantRow row = findGrant(command.grantId());
        if (row == null || !row.listenerId().equals(command.listenerId())) {
            throw new TrustOperationsAccessDeniedException();
        }
        GrantRow replay = findRevocationByOperation(command.operationKey());
        if (replay != null) {
            if (!replay.grantId().equals(command.grantId())) {
                throw new TrustOperationsConflictException();
            }
            return grant(replay, false);
        }
        if (command.expectedVersion() != 0 || row.revokedAt() != null) {
            throw new TrustOperationsPreconditionException();
        }
        Instant now = identityClock.instant();
        UUID notificationId = identifierGenerator.generate();
        auditJournal.notifyListener(new NotificationCommand(
                notificationId, command.listenerId(), row.caseId(), row.grantId(),
                "DELEGATED_ACCESS_REVOKED", now));
        jdbcTemplate.update(
                """
                INSERT INTO trust_operations.delegated_access_revocation (
                    grant_id, listener_id, operation_key, notification_id, revoked_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                row.grantId(),
                command.listenerId(),
                command.operationKey(),
                notificationId,
                Timestamp.from(now));
        auditJournal.append(new AuditCommand(
                row.caseId(),
                row.grantId(),
                command.listenerId(),
                identifierGenerator.generate(),
                "LISTENER_REVOCATION",
                findCase(row.caseId()).opaqueResourceReference(),
                row.purposeCode(),
                "DELEGATED_SUPPORT_ACCESS_V1",
                "REVOKE_DELEGATED_ACCESS",
                "REVOKED",
                now,
                command.operationKey(),
                notificationId,
                null,
                null));
        return new DelegatedAccessGrant(
                row.grantId(),
                row.caseId(),
                row.staffId(),
                row.purposeCode(),
                row.allowedActions(),
                row.validFrom(),
                row.expiresAt(),
                true,
                1,
                true);
    }

    @Override
    @Transactional(readOnly = true)
    public ListenerAccessSummary listenerAccess(UUID listenerId) {
        List<DelegatedAccessGrant> grants = jdbcTemplate.query(
                grantSelect() + " WHERE g.listener_id = ? ORDER BY g.valid_from DESC, g.grant_id",
                (resultSet, row) -> grant(grantRow(resultSet), false),
                listenerId);
        List<ListenerNotification> notifications = jdbcTemplate.query(
                """
                SELECT notification_id, case_id, grant_id, event_type, created_at
                FROM trust_operations.listener_notification
                WHERE listener_id = ?
                ORDER BY created_at DESC, notification_id
                """,
                (resultSet, row) -> new ListenerNotification(
                        resultSet.getObject("notification_id", UUID.class),
                        resultSet.getObject("case_id", UUID.class),
                        resultSet.getObject("grant_id", UUID.class),
                        resultSet.getString("event_type"),
                        resultSet.getTimestamp("created_at").toInstant()),
                listenerId);
        return new ListenerAccessSummary(grants, notifications);
    }

    @Override
    @Transactional
    public EmergencyAccessGrant grantEmergencyAccess(GrantEmergencyAccessCommand command) {
        Objects.requireNonNull(command, "command");
        String requestFingerprint = fingerprint(
                command.responder().staffId(),
                command.caseId(),
                command.incidentReference(),
                command.justificationCode(),
                command.purposeCode(),
                command.allowedActions().stream().map(Enum::name).sorted().toList(),
                command.expiresAt());
        EmergencyRow replay = findEmergencyByOperation(command.operationKey());
        if (replay != null) {
            if (!requestFingerprint.equals(replay.requestFingerprint())) {
                throw new TrustOperationsConflictException();
            }
            return emergencyGrant(replay, false);
        }

        CaseRow operationsCase = findCase(command.caseId());
        Instant now = identityClock.instant();
        policy.requireEmergencyAccess(
                command, operationsCase.type().requiredRole(), operationsCase.allowedActions(), now);
        UUID grantId = identifierGenerator.generate();
        UUID notificationId = operationsCase.listenerId() == null ? null : identifierGenerator.generate();
        Instant reviewDueAt = policy.emergencyReviewDueAt(now);
        if (notificationId != null) {
            auditJournal.notifyListener(new NotificationCommand(
                    notificationId, operationsCase.listenerId(), command.caseId(), grantId,
                    "EMERGENCY_ACCESS_STARTED", now));
        }
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO trust_operations.emergency_access_grant (
                        grant_id, case_id, listener_id, responder_id, incident_reference,
                        justification_code, purpose_code, allowed_actions, valid_from, expires_at,
                        review_due_at, operation_key, request_fingerprint, notification_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            statement.setObject(1, grantId);
            statement.setObject(2, command.caseId());
            statement.setObject(3, operationsCase.listenerId());
            statement.setObject(4, command.responder().staffId());
            statement.setString(5, command.incidentReference());
            statement.setString(6, command.justificationCode());
            statement.setString(7, command.purposeCode());
            statement.setArray(8, actionArray(connection, command.allowedActions()));
            statement.setTimestamp(9, Timestamp.from(now));
            statement.setTimestamp(10, Timestamp.from(command.expiresAt()));
            statement.setTimestamp(11, Timestamp.from(reviewDueAt));
            statement.setString(12, command.operationKey());
            statement.setString(13, requestFingerprint);
            statement.setObject(14, notificationId);
            return statement;
        });
        auditJournal.append(new AuditCommand(
                command.caseId(),
                grantId,
                command.responder().staffId(),
                identifierGenerator.generate(),
                "INCIDENT_RESPONDER",
                operationsCase.opaqueResourceReference(),
                command.purposeCode(),
                "EMERGENCY_ACCESS_V1",
                "GRANT_EMERGENCY_ACCESS",
                "GRANTED",
                now,
                command.operationKey(),
                notificationId,
                "INDEPENDENT_RETROSPECTIVE_REVIEW",
                "LISTENER_APPEAL_AVAILABLE"));
        return new EmergencyAccessGrant(
                grantId,
                command.caseId(),
                command.responder().staffId(),
                command.incidentReference(),
                command.justificationCode(),
                command.purposeCode(),
                command.allowedActions(),
                now,
                command.expiresAt(),
                reviewDueAt,
                "PENDING",
                null,
                null,
                true);
    }

    @Override
    @Transactional
    public EmergencyAccessGrant reviewEmergencyAccess(ReviewEmergencyAccessCommand command) {
        Objects.requireNonNull(command, "command");
        EmergencyRow emergency = findEmergency(command.grantId());
        if (emergency == null) {
            throw new TrustOperationsAccessDeniedException();
        }
        policy.requireEmergencyReview(command, emergency.responderId());
        EmergencyRow replay = findEmergencyReviewByOperation(command.operationKey());
        if (replay != null) {
            if (!replay.grantId().equals(command.grantId()) || replay.reviewOutcome() != command.outcome()) {
                throw new TrustOperationsConflictException();
            }
            return emergencyGrant(replay, false);
        }
        policy.requireEmergencyReviewOpen(emergency.reviewedAt());
        Instant now = identityClock.instant();
        jdbcTemplate.update(
                """
                INSERT INTO trust_operations.emergency_access_review (
                    grant_id, responder_id, reviewer_id, outcome, review_code, operation_key, reviewed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                emergency.grantId(),
                emergency.responderId(),
                command.reviewer().staffId(),
                command.outcome().name(),
                command.reviewCode(),
                command.operationKey(),
                Timestamp.from(now));
        CaseRow operationsCase = findCase(emergency.caseId());
        auditJournal.append(new AuditCommand(
                emergency.caseId(),
                emergency.grantId(),
                command.reviewer().staffId(),
                identifierGenerator.generate(),
                "SECURITY_REVIEWER",
                operationsCase.opaqueResourceReference(),
                "RETROSPECTIVE_REVIEW",
                "EMERGENCY_ACCESS_V1",
                "REVIEW_EMERGENCY_ACCESS",
                command.outcome().name(),
                now,
                command.operationKey(),
                null,
                null,
                command.outcome() == EmergencyReviewOutcome.UNJUSTIFIED
                        ? "LISTENER_APPEAL_AND_SECURITY_ESCALATION"
                        : null));
        return new EmergencyAccessGrant(
                emergency.grantId(),
                emergency.caseId(),
                emergency.responderId(),
                emergency.incidentReference(),
                emergency.justificationCode(),
                emergency.purposeCode(),
                emergency.allowedActions(),
                emergency.validFrom(),
                emergency.expiresAt(),
                emergency.reviewDueAt(),
                command.outcome().name(),
                command.reviewer().staffId(),
                now,
                true);
    }

    @Override
    @Transactional
    public PrivilegedActionResult performPrivilegedAction(PrivilegedActionCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.staff(), "staff");
        Objects.requireNonNull(command.action(), "action");
        TrustOperationsPolicy.requireOperationKey(command.operationKey());
        CaseRow operationsCase = findCase(command.caseId());
        if (!command.staff().roles().contains(operationsCase.type().requiredRole())) {
            throw new TrustOperationsAccessDeniedException();
        }
        AccessAuthority authority = activeAuthority(
                command.staff().staffId(), command.caseId(), command.action());
        if (authority == null) {
            throw new TrustOperationsAccessDeniedException();
        }
        AuditReplay replay = auditJournal.findByCorrelation(command.operationKey());
        if (replay != null) {
            if (!replay.actorId().equals(command.staff().staffId())
                    || !replay.grantId().equals(authority.grantId())
                    || !replay.event().action().equals(command.action().name())) {
                throw new TrustOperationsConflictException();
            }
            return new PrivilegedActionResult(
                    new AuthorizedResource(operationsCase.resourceKind(), operationsCase.resourceId()),
                    replay.event());
        }
        if (command.action() != PrivilegedAction.VIEW_RESOURCE_REFERENCE) {
            throw new IllegalArgumentException("Unsupported privileged action");
        }
        Instant now = identityClock.instant();
        UUID notificationId = authority.listenerId() == null ? null : identifierGenerator.generate();
        if (notificationId != null) {
            auditJournal.notifyListener(new NotificationCommand(
                    notificationId, authority.listenerId(), command.caseId(), authority.grantId(),
                    "PRIVILEGED_ACTION_PERFORMED", now));
        }
        AuditEvent auditEvent = auditJournal.append(new AuditCommand(
                command.caseId(),
                authority.grantId(),
                command.staff().staffId(),
                identifierGenerator.generate(),
                authority.authority(),
                operationsCase.opaqueResourceReference(),
                authority.purposeCode(),
                authority.policyCode(),
                command.action().name(),
                "DISCLOSED",
                now,
                command.operationKey(),
                notificationId,
                authority.reviewObligation(),
                "LISTENER_APPEAL_AVAILABLE"));
        return new PrivilegedActionResult(
                new AuthorizedResource(operationsCase.resourceKind(), operationsCase.resourceId()),
                auditEvent);
    }

    private AccessAuthority activeAuthority(UUID staffId, UUID caseId, PrivilegedAction action) {
        Timestamp now = Timestamp.from(identityClock.instant());
        List<AccessAuthority> delegated = jdbcTemplate.query(
                """
                SELECT g.grant_id, g.listener_id, g.purpose_code
                FROM trust_operations.delegated_access_grant g
                LEFT JOIN trust_operations.delegated_access_revocation r ON r.grant_id = g.grant_id
                WHERE g.case_id = ? AND g.staff_id = ? AND r.grant_id IS NULL
                  AND g.valid_from <= ? AND g.expires_at > ? AND ? = ANY (g.allowed_actions)
                ORDER BY g.expires_at LIMIT 1
                """,
                (resultSet, row) -> new AccessAuthority(
                        resultSet.getObject("grant_id", UUID.class),
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getString("purpose_code"),
                        "DELEGATED_SUPPORT_ACCESS",
                        "DELEGATED_SUPPORT_ACCESS_V1",
                        null),
                caseId,
                staffId,
                now,
                now,
                action.name());
        if (!delegated.isEmpty()) {
            return delegated.getFirst();
        }
        List<AccessAuthority> emergency = jdbcTemplate.query(
                """
                SELECT g.grant_id, g.listener_id, g.purpose_code
                FROM trust_operations.emergency_access_grant g
                WHERE g.case_id = ? AND g.responder_id = ?
                  AND g.valid_from <= ? AND g.expires_at > ? AND ? = ANY (g.allowed_actions)
                ORDER BY g.expires_at LIMIT 1
                """,
                (resultSet, row) -> new AccessAuthority(
                        resultSet.getObject("grant_id", UUID.class),
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getString("purpose_code"),
                        "EMERGENCY_ACCESS",
                        "EMERGENCY_ACCESS_V1",
                        "INDEPENDENT_RETROSPECTIVE_REVIEW"),
                caseId,
                staffId,
                now,
                now,
                action.name());
        return emergency.isEmpty() ? null : emergency.getFirst();
    }

    private CaseRow findCase(UUID caseId) {
        List<CaseRow> rows = jdbcTemplate.query(
                """
                SELECT case_id, case_type, listener_id, resource_kind, resource_id,
                       opaque_resource_reference, restriction_code, consequence_code,
                       deadline, safety_priority, urgency, allowed_actions, correlation_id
                FROM trust_operations.operations_case
                WHERE case_id = ? AND resolved_at IS NULL
                """,
                (resultSet, row) -> new CaseRow(
                        resultSet.getObject("case_id", UUID.class),
                        CaseType.valueOf(resultSet.getString("case_type")),
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getString("resource_kind"),
                        resultSet.getObject("resource_id", UUID.class),
                        resultSet.getObject("opaque_resource_reference", UUID.class),
                        resultSet.getString("restriction_code"),
                        resultSet.getString("consequence_code"),
                        resultSet.getTimestamp("deadline").toInstant(),
                        resultSet.getInt("safety_priority"),
                        resultSet.getInt("urgency"),
                        actions(resultSet.getArray("allowed_actions")),
                        resultSet.getString("correlation_id")),
                caseId);
        if (rows.isEmpty()) {
            throw new TrustOperationsAccessDeniedException();
        }
        return rows.getFirst();
    }

    private CaseRow findCaseByCorrelation(String correlationId) {
        List<CaseRow> rows = jdbcTemplate.query(
                """
                SELECT case_id, case_type, listener_id, resource_kind, resource_id,
                       opaque_resource_reference, restriction_code, consequence_code,
                       deadline, safety_priority, urgency, allowed_actions, correlation_id
                FROM trust_operations.operations_case
                WHERE correlation_id = ?
                """,
                (resultSet, row) -> new CaseRow(
                        resultSet.getObject("case_id", UUID.class),
                        CaseType.valueOf(resultSet.getString("case_type")),
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getString("resource_kind"),
                        resultSet.getObject("resource_id", UUID.class),
                        resultSet.getObject("opaque_resource_reference", UUID.class),
                        resultSet.getString("restriction_code"),
                        resultSet.getString("consequence_code"),
                        resultSet.getTimestamp("deadline").toInstant(),
                        resultSet.getInt("safety_priority"),
                        resultSet.getInt("urgency"),
                        actions(resultSet.getArray("allowed_actions")),
                        resultSet.getString("correlation_id")),
                correlationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static OperationsCase operationsCase(CaseRow row) {
        return new OperationsCase(row.caseId(), row.type(), row.opaqueResourceReference(), row.resourceKind(),
                row.restrictionCode(), row.consequenceCode(), row.deadline(), row.safetyPriority(), row.urgency(),
                row.allowedActions());
    }

    private GrantRow findGrant(UUID grantId) {
        return singleGrant(grantSelect() + " WHERE g.grant_id = ?", grantId);
    }

    private GrantRow findGrantByOperation(String operationKey) {
        TrustOperationsPolicy.requireOperationKey(operationKey);
        return singleGrant(grantSelect() + " WHERE g.operation_key = ?", operationKey);
    }

    private GrantRow findRevocationByOperation(String operationKey) {
        TrustOperationsPolicy.requireOperationKey(operationKey);
        return singleGrant(grantSelect() + " WHERE r.operation_key = ?", operationKey);
    }

    private GrantRow singleGrant(String sql, Object argument) {
        List<GrantRow> rows = jdbcTemplate.query(sql, (resultSet, row) -> grantRow(resultSet), argument);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String grantSelect() {
        return """
                SELECT g.grant_id, g.case_id, g.listener_id, g.staff_id, g.purpose_code,
                       g.allowed_actions, g.valid_from, g.expires_at, g.operation_key,
                       g.request_fingerprint, r.revoked_at
                FROM trust_operations.delegated_access_grant g
                LEFT JOIN trust_operations.delegated_access_revocation r ON r.grant_id = g.grant_id
                """;
    }

    private static GrantRow grantRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Timestamp revokedAt = resultSet.getTimestamp("revoked_at");
        return new GrantRow(
                resultSet.getObject("grant_id", UUID.class),
                resultSet.getObject("case_id", UUID.class),
                resultSet.getObject("listener_id", UUID.class),
                resultSet.getObject("staff_id", UUID.class),
                resultSet.getString("purpose_code"),
                actions(resultSet.getArray("allowed_actions")),
                resultSet.getTimestamp("valid_from").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getString("operation_key"),
                resultSet.getString("request_fingerprint"),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    private static DelegatedAccessGrant grant(GrantRow row, boolean created) {
        return new DelegatedAccessGrant(
                row.grantId(),
                row.caseId(),
                row.staffId(),
                row.purposeCode(),
                row.allowedActions(),
                row.validFrom(),
                row.expiresAt(),
                row.revokedAt() != null,
                row.revokedAt() == null ? 0 : 1,
                created);
    }

    private EmergencyRow findEmergency(UUID grantId) {
        return singleEmergency(emergencySelect() + " WHERE g.grant_id = ?", grantId);
    }

    private EmergencyRow findEmergencyByOperation(String operationKey) {
        TrustOperationsPolicy.requireOperationKey(operationKey);
        return singleEmergency(emergencySelect() + " WHERE g.operation_key = ?", operationKey);
    }

    private EmergencyRow findEmergencyReviewByOperation(String operationKey) {
        TrustOperationsPolicy.requireOperationKey(operationKey);
        return singleEmergency(emergencySelect() + " WHERE r.operation_key = ?", operationKey);
    }

    private EmergencyRow singleEmergency(String sql, Object argument) {
        List<EmergencyRow> rows = jdbcTemplate.query(sql, (resultSet, row) -> emergencyRow(resultSet), argument);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String emergencySelect() {
        return """
                SELECT g.grant_id, g.case_id, g.listener_id, g.responder_id,
                       g.incident_reference, g.justification_code, g.purpose_code,
                       g.allowed_actions, g.valid_from, g.expires_at, g.review_due_at,
                       g.operation_key, g.request_fingerprint, r.reviewer_id,
                       r.outcome, r.reviewed_at
                FROM trust_operations.emergency_access_grant g
                LEFT JOIN trust_operations.emergency_access_review r ON r.grant_id = g.grant_id
                """;
    }

    private static EmergencyRow emergencyRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        String outcome = resultSet.getString("outcome");
        Timestamp reviewedAt = resultSet.getTimestamp("reviewed_at");
        return new EmergencyRow(
                resultSet.getObject("grant_id", UUID.class),
                resultSet.getObject("case_id", UUID.class),
                resultSet.getObject("listener_id", UUID.class),
                resultSet.getObject("responder_id", UUID.class),
                resultSet.getString("incident_reference"),
                resultSet.getString("justification_code"),
                resultSet.getString("purpose_code"),
                actions(resultSet.getArray("allowed_actions")),
                resultSet.getTimestamp("valid_from").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("review_due_at").toInstant(),
                resultSet.getString("operation_key"),
                resultSet.getString("request_fingerprint"),
                resultSet.getObject("reviewer_id", UUID.class),
                outcome == null ? null : EmergencyReviewOutcome.valueOf(outcome),
                reviewedAt == null ? null : reviewedAt.toInstant());
    }

    private static EmergencyAccessGrant emergencyGrant(EmergencyRow row, boolean created) {
        return new EmergencyAccessGrant(
                row.grantId(),
                row.caseId(),
                row.responderId(),
                row.incidentReference(),
                row.justificationCode(),
                row.purposeCode(),
                row.allowedActions(),
                row.validFrom(),
                row.expiresAt(),
                row.reviewDueAt(),
                row.reviewOutcome() == null ? "PENDING" : row.reviewOutcome().name(),
                row.reviewerId(),
                row.reviewedAt(),
                created);
    }

    private static Array actionArray(java.sql.Connection connection, Set<PrivilegedAction> actions)
            throws java.sql.SQLException {
        return connection.createArrayOf(
                "varchar", actions.stream().map(Enum::name).sorted().toArray(String[]::new));
    }

    private static Set<PrivilegedAction> actions(Array array) throws java.sql.SQLException {
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values)
                .map(String::valueOf)
                .map(PrivilegedAction::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record CaseRow(
            UUID caseId,
            CaseType type,
            UUID listenerId,
            String resourceKind,
            UUID resourceId,
            UUID opaqueResourceReference,
            String restrictionCode,
            String consequenceCode,
            Instant deadline,
            int safetyPriority,
            int urgency,
            Set<PrivilegedAction> allowedActions,
            String correlationId) {

        boolean matches(OpenCaseRequest request) {
            return type == request.type()
                    && Objects.equals(listenerId, request.listenerId())
                    && resourceKind.equals(request.resourceKind())
                    && resourceId.equals(request.resourceId())
                    && restrictionCode.equals(request.restrictionCode())
                    && consequenceCode.equals(request.consequenceCode())
                    && deadline.equals(request.deadline())
                    && safetyPriority == request.safetyPriority()
                    && urgency == request.urgency()
                    && allowedActions.equals(request.allowedActions());
        }
    }

    private record GrantRow(
            UUID grantId,
            UUID caseId,
            UUID listenerId,
            UUID staffId,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant validFrom,
            Instant expiresAt,
            String operationKey,
            String requestFingerprint,
            Instant revokedAt) {
    }

    private record EmergencyRow(
            UUID grantId,
            UUID caseId,
            UUID listenerId,
            UUID responderId,
            String incidentReference,
            String justificationCode,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant validFrom,
            Instant expiresAt,
            Instant reviewDueAt,
            String operationKey,
            String requestFingerprint,
            UUID reviewerId,
            EmergencyReviewOutcome reviewOutcome,
            Instant reviewedAt) {
    }

    private record AccessAuthority(
            UUID grantId,
            UUID listenerId,
            String purposeCode,
            String authority,
            String policyCode,
            String reviewObligation) {
    }

}
