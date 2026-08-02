package dev.audiobook.platform.trustoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.trustoperations.policy.TrustOperationsPolicy;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.ApproveDelegatedAccessCommand;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.CaseType;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.EmergencyReviewOutcome;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.GrantEmergencyAccessCommand;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.OpenCaseRequest;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.PrivilegedAction;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.ReviewEmergencyAccessCommand;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.StaffContext;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.StaffRole;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

class TrustOperationsPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-01T20:00:00Z");
    private static final UUID LISTENER = UUID.fromString("01985f42-5f8d-7000-8000-000000000036");
    private static final UUID STAFF = UUID.fromString("01985f42-5f8d-7000-8000-000000000136");
    private static final UUID CASE = UUID.fromString("01985f42-5f8d-7000-8000-000000000236");
    private final TrustOperationsPolicy policy =
            new TrustOperationsPolicy(
                    new TrustOperationsProperties(
                            Duration.ofHours(24),
                            Duration.ofMinutes(30),
                            Duration.ofHours(24),
                            Duration.ofMinutes(5)));

    @Test
    void delegatedApprovalAcceptsTheExactMaximumAndRejectsWrongListenerScopeAndExpiry() {
        ApproveDelegatedAccessCommand valid =
                delegated(
                        LISTENER,
                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                        NOW.plus(Duration.ofHours(24)));
        assertThatNoException()
                .isThrownBy(
                        () ->
                                policy.requireDelegatedApproval(
                                        valid,
                                        LISTENER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW));

        assertThatThrownBy(
                        () ->
                                policy.requireDelegatedApproval(
                                        valid,
                                        UUID.randomUUID(),
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW))
                .isInstanceOf(TrustOperationsAccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                policy.requireDelegatedApproval(
                                        delegated(LISTENER, Set.of(), NOW.plusSeconds(60)),
                                                LISTENER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                policy.requireDelegatedApproval(
                                        delegated(
                                                LISTENER,
                                                Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                                NOW.plus(Duration.ofHours(24)).plusNanos(1)),
                                        LISTENER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emergencyAccessRequiresIncidentRoleFreshMfaMinimumScopeAndHardExpiry() {
        GrantEmergencyAccessCommand valid =
                emergency(
                        Set.of(StaffRole.INCIDENT_RESPONDER),
                        NOW.minus(Duration.ofMinutes(5)),
                        NOW.plus(Duration.ofMinutes(30)));
        assertThatNoException()
                .isThrownBy(
                        () ->
                                policy.requireEmergencyAccess(
                                        valid,
                                        StaffRole.INCIDENT_RESPONDER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW));

        assertThatThrownBy(
                        () ->
                                policy.requireEmergencyAccess(
                                        emergency(
                                                Set.of(StaffRole.SUPPORT),
                                                NOW,
                                                NOW.plusSeconds(60)),
                                        StaffRole.INCIDENT_RESPONDER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW))
                .isInstanceOf(TrustOperationsForbiddenException.class);
        assertThatThrownBy(
                        () ->
                                policy.requireEmergencyAccess(
                                        emergency(
                                                Set.of(StaffRole.INCIDENT_RESPONDER),
                                                NOW.minus(Duration.ofMinutes(5)).minusNanos(1),
                                                NOW.plusSeconds(60)),
                                        StaffRole.INCIDENT_RESPONDER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW))
                .isInstanceOf(TrustOperationsFreshMfaRequiredException.class);
        assertThatThrownBy(
                        () ->
                                policy.requireEmergencyAccess(
                                        emergency(
                                                Set.of(StaffRole.INCIDENT_RESPONDER),
                                                NOW,
                                                NOW.plus(Duration.ofMinutes(30)).plusNanos(1)),
                                        StaffRole.INCIDENT_RESPONDER,
                                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                        NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.emergencyReviewDueAt(NOW)).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void operationKeysAreRequiredAndBounded() {
        assertThatThrownBy(() -> TrustOperationsPolicy.requireOperationKey(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustOperationsPolicy.requireOperationKey("x".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException()
                .isThrownBy(() -> TrustOperationsPolicy.requireOperationKey("operation-36"));
    }

    @Test
    void emergencyReviewRequiresAnIndependentSecurityReviewerAndAnOpenReview() {
        ReviewEmergencyAccessCommand valid =
                new ReviewEmergencyAccessCommand(
                        new StaffContext(STAFF, Set.of(StaffRole.SECURITY_REVIEWER), NOW),
                        CASE,
                        EmergencyReviewOutcome.APPROPRIATE,
                        "POLICY_FOLLOWED",
                        "review-36");
        assertThatNoException()
                .isThrownBy(() -> policy.requireEmergencyReview(valid, UUID.randomUUID()));
        assertThatThrownBy(() -> policy.requireEmergencyReview(valid, STAFF))
                .isInstanceOf(TrustOperationsForbiddenException.class);
        assertThatThrownBy(
                        () ->
                                policy.requireEmergencyReview(
                                        new ReviewEmergencyAccessCommand(
                                                new StaffContext(
                                                        STAFF, Set.of(StaffRole.SUPPORT), NOW),
                                                CASE,
                                                EmergencyReviewOutcome.APPROPRIATE,
                                                "POLICY_FOLLOWED",
                                                "review-36"),
                                        UUID.randomUUID()))
                .isInstanceOf(TrustOperationsForbiddenException.class);
        assertThatThrownBy(() -> policy.requireEmergencyReviewOpen(NOW))
                .isInstanceOf(TrustOperationsPreconditionException.class);
    }

    @Test
    void caseIntakeRequiresBoundedPrioritiesContextActionsAndCorrelation() {
        OpenCaseRequest valid =
                new OpenCaseRequest(
                        CaseType.SUPPORT,
                        LISTENER,
                        "PRIVATE_AUDIOBOOK",
                        UUID.randomUUID(),
                        "PLAYBACK_SUPPORT",
                        "PLAYBACK_UNAVAILABLE",
                        NOW.plusSeconds(600),
                        80,
                        70,
                        Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                        "case-36");
        assertThatNoException().isThrownBy(() -> policy.requireValidCase(valid));
        assertThatThrownBy(
                        () ->
                                policy.requireValidCase(
                                        new OpenCaseRequest(
                                                CaseType.SUPPORT,
                                                LISTENER,
                                                "PRIVATE_AUDIOBOOK",
                                                UUID.randomUUID(),
                                                "PLAYBACK_SUPPORT",
                                                "PLAYBACK_UNAVAILABLE",
                                                NOW.plusSeconds(600),
                                                -1,
                                                70,
                                                Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                                "case-36")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                policy.requireValidCase(
                                        new OpenCaseRequest(
                                                CaseType.SUPPORT,
                                                LISTENER,
                                                "PRIVATE_AUDIOBOOK",
                                                UUID.randomUUID(),
                                                "PLAYBACK_SUPPORT",
                                                "PLAYBACK_UNAVAILABLE",
                                                NOW.plusSeconds(600),
                                                80,
                                                70,
                                                Set.of(),
                                                "case-36")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ApproveDelegatedAccessCommand delegated(
            UUID listener, Set<PrivilegedAction> actions, Instant expiresAt) {
        return new ApproveDelegatedAccessCommand(
                listener, CASE, STAFF, "RESTORE_PLAYBACK", actions, expiresAt, "approval-36");
    }

    private static GrantEmergencyAccessCommand emergency(
            Set<StaffRole> roles, Instant authenticatedAt, Instant expiresAt) {
        return new GrantEmergencyAccessCommand(
                new StaffContext(STAFF, roles, authenticatedAt),
                CASE,
                "INC-36",
                "OUTAGE",
                "RESTORE_PLAYBACK",
                Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                expiresAt,
                "emergency-36");
    }
}
