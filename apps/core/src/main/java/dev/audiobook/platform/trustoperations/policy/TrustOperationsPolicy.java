package dev.audiobook.platform.trustoperations.policy;

import dev.audiobook.platform.trustoperations.*;
import dev.audiobook.platform.trustoperations.TrustOperationsProperties;
import dev.audiobook.platform.trustoperations.service.*;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.ApproveDelegatedAccessCommand;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.GrantEmergencyAccessCommand;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.OpenCaseRequest;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.PrivilegedAction;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.ReviewEmergencyAccessCommand;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.StaffRole;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public final class TrustOperationsPolicy {

    private final TrustOperationsProperties properties;

    public void requireValidCase(OpenCaseRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.type(), "type");
        Objects.requireNonNull(request.resourceKind(), "resourceKind");
        Objects.requireNonNull(request.resourceId(), "resourceId");
        Objects.requireNonNull(request.restrictionCode(), "restrictionCode");
        Objects.requireNonNull(request.consequenceCode(), "consequenceCode");
        Objects.requireNonNull(request.deadline(), "deadline");
        requireOperationKey(request.correlationId());
        if (request.resourceKind().isBlank()
                || request.restrictionCode().isBlank()
                || request.consequenceCode().isBlank()
                || request.allowedActions().isEmpty()
                || request.safetyPriority() < 0
                || request.urgency() < 0) {
            throw new IllegalArgumentException("Invalid operations case");
        }
    }

    public void requireDelegatedApproval(
            ApproveDelegatedAccessCommand command,
            java.util.UUID caseListenerId,
            Set<PrivilegedAction> caseActions,
            Instant now) {
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.staffId(), "staffId");
        Objects.requireNonNull(command.purposeCode(), "purposeCode");
        Objects.requireNonNull(command.expiresAt(), "expiresAt");
        requireOperationKey(command.operationKey());
        if (!command.listenerId().equals(caseListenerId)) {
            throw new TrustOperationsAccessDeniedException();
        }
        if (command.purposeCode().isBlank()
                || command.allowedActions().isEmpty()
                || !caseActions.containsAll(command.allowedActions())
                || !command.expiresAt().isAfter(now)
                || command.expiresAt()
                        .isAfter(now.plus(properties.delegatedAccessMaximumDuration()))) {
            throw new IllegalArgumentException("Invalid delegated access grant");
        }
    }

    public void requireEmergencyAccess(
            GrantEmergencyAccessCommand command,
            StaffRole requiredRole,
            Set<PrivilegedAction> caseActions,
            Instant now) {
        Objects.requireNonNull(command.responder(), "responder");
        Objects.requireNonNull(command.expiresAt(), "expiresAt");
        requireOperationKey(command.operationKey());
        if (!command.responder().roles().contains(StaffRole.INCIDENT_RESPONDER)
                || requiredRole != StaffRole.INCIDENT_RESPONDER) {
            throw new TrustOperationsForbiddenException();
        }
        Instant authenticatedAt = command.responder().authenticatedAt();
        if (authenticatedAt == null
                || authenticatedAt.isBefore(now.minus(properties.freshMfaMaximumAge()))
                || authenticatedAt.isAfter(now.plusSeconds(30))) {
            throw new TrustOperationsFreshMfaRequiredException();
        }
        if (blank(command.incidentReference())
                || blank(command.justificationCode())
                || blank(command.purposeCode())
                || command.allowedActions().isEmpty()
                || !caseActions.containsAll(command.allowedActions())
                || !command.expiresAt().isAfter(now)
                || command.expiresAt()
                        .isAfter(now.plus(properties.emergencyAccessMaximumDuration()))) {
            throw new IllegalArgumentException("Invalid Emergency Access grant");
        }
    }

    public Instant emergencyReviewDueAt(Instant validFrom) {
        return validFrom.plus(properties.emergencyReviewDeadline());
    }

    public void requireEmergencyReview(ReviewEmergencyAccessCommand command, UUID responderId) {
        Objects.requireNonNull(command.reviewer(), "reviewer");
        requireOperationKey(command.operationKey());
        if (!command.reviewer().roles().contains(StaffRole.SECURITY_REVIEWER)
                || responderId.equals(command.reviewer().staffId())) {
            throw new TrustOperationsForbiddenException();
        }
        if (command.outcome() == null || blank(command.reviewCode())) {
            throw new IllegalArgumentException("Invalid Emergency Access review");
        }
    }

    public void requireEmergencyReviewOpen(Instant reviewedAt) {
        if (reviewedAt != null) {
            throw new TrustOperationsPreconditionException();
        }
    }

    public static void requireOperationKey(String operationKey) {
        if (operationKey == null || operationKey.isBlank() || operationKey.length() > 200) {
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
