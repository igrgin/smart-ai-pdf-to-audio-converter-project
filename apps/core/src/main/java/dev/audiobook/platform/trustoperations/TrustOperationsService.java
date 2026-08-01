package dev.audiobook.platform.trustoperations;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface TrustOperationsService {

    OperationsCase openCase(OpenCaseRequest request);

    List<OperationsCase> actionQueue(Set<StaffRole> roles);

    CaseDetails caseDetails(StaffContext staff, UUID caseId);

    DelegatedAccessGrant approveDelegatedAccess(ApproveDelegatedAccessCommand command);

    DelegatedAccessGrant revokeDelegatedAccess(RevokeDelegatedAccessCommand command);

    ListenerAccessSummary listenerAccess(UUID listenerId);

    EmergencyAccessGrant grantEmergencyAccess(GrantEmergencyAccessCommand command);

    EmergencyAccessGrant reviewEmergencyAccess(ReviewEmergencyAccessCommand command);

    PrivilegedActionResult performPrivilegedAction(PrivilegedActionCommand command);

    enum CaseType {
        SUPPORT(StaffRole.SUPPORT),
        EXPIRING_ACCESS(StaffRole.SUPPORT),
        FAILED_STAGE(StaffRole.RELIABILITY),
        ENTITLEMENT_INTERVENTION(StaffRole.ENTITLEMENT),
        VOICE_AVAILABILITY(StaffRole.VOICE),
        SERVICE_INCIDENT(StaffRole.INCIDENT_RESPONDER);

        private final StaffRole requiredRole;

        CaseType(StaffRole requiredRole) {
            this.requiredRole = requiredRole;
        }

        public StaffRole requiredRole() {
            return requiredRole;
        }
    }

    enum StaffRole {
        SUPPORT,
        RELIABILITY,
        ENTITLEMENT,
        VOICE,
        INCIDENT_RESPONDER,
        SECURITY_REVIEWER;

        public String authority() {
            return "ROLE_" + name();
        }
    }

    enum PrivilegedAction {
        VIEW_RESOURCE_REFERENCE
    }

    enum EmergencyReviewOutcome {
        APPROPRIATE,
        POLICY_GAP,
        UNJUSTIFIED
    }

    record OpenCaseRequest(
            CaseType type,
            UUID listenerId,
            String resourceKind,
            UUID resourceId,
            String restrictionCode,
            String consequenceCode,
            Instant deadline,
            int safetyPriority,
            int urgency,
            Set<PrivilegedAction> allowedActions,
            String correlationId) {

        public OpenCaseRequest {
            allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        }
    }

    record OperationsCase(
            UUID caseId,
            CaseType type,
            UUID opaqueResourceReference,
            String resourceKind,
            String restrictionCode,
            String consequenceCode,
            Instant deadline,
            int safetyPriority,
            int urgency,
            Set<PrivilegedAction> allowedActions) {

        public OperationsCase {
            allowedActions = Set.copyOf(allowedActions);
        }
    }

    record StaffContext(UUID staffId, Set<StaffRole> roles, Instant authenticatedAt) {
        public StaffContext {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    record ApproveDelegatedAccessCommand(
            UUID listenerId,
            UUID caseId,
            UUID staffId,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant expiresAt,
            String operationKey) {

        public ApproveDelegatedAccessCommand {
            allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        }
    }

    record RevokeDelegatedAccessCommand(
            UUID listenerId,
            UUID grantId,
            long expectedVersion,
            String operationKey) {
    }

    record DelegatedAccessGrant(
            UUID grantId,
            UUID caseId,
            UUID staffId,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant validFrom,
            Instant expiresAt,
            boolean revoked,
            long version,
            boolean created) {

        public DelegatedAccessGrant {
            allowedActions = Set.copyOf(allowedActions);
        }
    }

    record CaseDetails(
            UUID caseId,
            CaseType type,
            UUID opaqueResourceReference,
            String resourceKind,
            String restrictionCode,
            String consequenceCode,
            Instant deadline,
            Set<PrivilegedAction> allowedActions,
            List<AuditEvent> auditEvents) {

        public CaseDetails {
            allowedActions = Set.copyOf(allowedActions);
            auditEvents = List.copyOf(auditEvents);
        }
    }

    record AuthorizedResource(String kind, UUID id) {
    }

    record AuditEvent(
            UUID eventId,
            UUID actorReference,
            String authority,
            UUID targetReference,
            String purposeCode,
            String policyCode,
            String action,
            String outcome,
            Instant occurredAt,
            String correlationId,
            UUID notificationId,
            String reviewObligation,
            String appealObligation) {
    }

    record ListenerNotification(
            UUID notificationId,
            UUID caseId,
            UUID grantId,
            String eventType,
            Instant createdAt) {
    }

    record ListenerAccessSummary(
            List<DelegatedAccessGrant> grants,
            List<ListenerNotification> notifications) {

        public ListenerAccessSummary {
            grants = List.copyOf(grants);
            notifications = List.copyOf(notifications);
        }
    }

    record GrantEmergencyAccessCommand(
            StaffContext responder,
            UUID caseId,
            String incidentReference,
            String justificationCode,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant expiresAt,
            String operationKey) {

        public GrantEmergencyAccessCommand {
            allowedActions = allowedActions == null ? Set.of() : Set.copyOf(allowedActions);
        }
    }

    record ReviewEmergencyAccessCommand(
            StaffContext reviewer,
            UUID grantId,
            EmergencyReviewOutcome outcome,
            String reviewCode,
            String operationKey) {
    }

    record EmergencyAccessGrant(
            UUID grantId,
            UUID caseId,
            UUID responderId,
            String incidentReference,
            String justificationCode,
            String purposeCode,
            Set<PrivilegedAction> allowedActions,
            Instant validFrom,
            Instant expiresAt,
            Instant reviewDueAt,
            String reviewStatus,
            UUID reviewerId,
            Instant reviewedAt,
            boolean created) {

        public EmergencyAccessGrant {
            allowedActions = Set.copyOf(allowedActions);
        }
    }

    record PrivilegedActionCommand(
            StaffContext staff,
            UUID caseId,
            PrivilegedAction action,
            String operationKey) {
    }

    record PrivilegedActionResult(
            AuthorizedResource authorizedResource,
            AuditEvent auditEvent) {
    }
}
