package dev.audiobook.platform.trustoperations.internal;

import dev.audiobook.platform.trustoperations.TrustOperationsService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/action-queue")
@RequiredArgsConstructor
public class TrustOperationsController {

    private final TrustOperationsService trustOperationsService;
    private final TrustOperationsCaseProjector caseProjector;
    private final DelegatedAccessRequestWorkflow delegatedAccessRequests;

    @GetMapping
    public ResponseEntity<ActionQueueView> actionQueue(Authentication authentication) {
        caseProjector.projectAuthoritativeCases();
        List<TrustOperationsService.OperationsCase> cases = trustOperationsService.actionQueue(
                StaffAuthorities.roles(authentication));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ActionQueueView(cases));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<TrustOperationsService.CaseDetails> caseDetails(
            @PathVariable UUID caseId,
            Authentication authentication) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(trustOperationsService.caseDetails(StaffAuthorities.context(authentication), caseId));
    }

    @PostMapping("/{caseId}/delegated-access-requests")
    public ResponseEntity<DelegatedAccessRequestWorkflow.PendingAccessRequest> requestDelegatedAccess(
            @PathVariable UUID caseId,
            @RequestBody DelegatedAccessRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        var pending = delegatedAccessRequests.request(
                StaffAuthorities.context(authentication), caseId, request.purposeCode(),
                request.allowedActions(), request.expiresAt(), operationKey);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(pending);
    }

    @PostMapping("/{caseId}/emergency-access")
    public ResponseEntity<TrustOperationsService.EmergencyAccessGrant> grantEmergencyAccess(
            @PathVariable UUID caseId,
            @RequestBody EmergencyAccessRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        TrustOperationsService.EmergencyAccessGrant grant = trustOperationsService.grantEmergencyAccess(
                new TrustOperationsService.GrantEmergencyAccessCommand(
                        StaffAuthorities.context(authentication),
                        caseId,
                        request.incidentReference(),
                        request.justificationCode(),
                        request.purposeCode(),
                        request.allowedActions(),
                        request.expiresAt(),
                        operationKey));
        HttpStatus status = grant.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(grant);
    }

    @PostMapping("/emergency-access-grants/{grantId}/review")
    public ResponseEntity<TrustOperationsService.EmergencyAccessGrant> reviewEmergencyAccess(
            @PathVariable UUID grantId,
            @RequestBody EmergencyReviewRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        TrustOperationsService.EmergencyAccessGrant grant = trustOperationsService.reviewEmergencyAccess(
                new TrustOperationsService.ReviewEmergencyAccessCommand(
                        StaffAuthorities.context(authentication),
                        grantId,
                        request.outcome(),
                        request.reviewCode(),
                        operationKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grant);
    }

    @PostMapping("/{caseId}/actions")
    public ResponseEntity<TrustOperationsService.PrivilegedActionResult> performPrivilegedAction(
            @PathVariable UUID caseId,
            @RequestBody PrivilegedActionRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        TrustOperationsService.PrivilegedActionResult result = trustOperationsService.performPrivilegedAction(
                new TrustOperationsService.PrivilegedActionCommand(
                        StaffAuthorities.context(authentication),
                        caseId,
                        request.action(),
                        operationKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    record ActionQueueView(List<TrustOperationsService.OperationsCase> cases) {
        ActionQueueView {
            cases = List.copyOf(cases);
        }
    }

    record EmergencyAccessRequest(
            String incidentReference,
            String justificationCode,
            String purposeCode,
            Set<TrustOperationsService.PrivilegedAction> allowedActions,
            Instant expiresAt) {
    }

    record DelegatedAccessRequest(
            String purposeCode,
            Set<TrustOperationsService.PrivilegedAction> allowedActions,
            Instant expiresAt) {
    }

    record EmergencyReviewRequest(
            TrustOperationsService.EmergencyReviewOutcome outcome,
            String reviewCode) {
    }

    record PrivilegedActionRequest(TrustOperationsService.PrivilegedAction action) {
    }

    static final class StaffAuthorities {
        private StaffAuthorities() {
        }

        static EnumSet<TrustOperationsService.StaffRole> roles(Authentication authentication) {
            EnumSet<TrustOperationsService.StaffRole> roles = EnumSet.noneOf(TrustOperationsService.StaffRole.class);
            if (authentication == null) {
                return roles;
            }
            for (TrustOperationsService.StaffRole role : TrustOperationsService.StaffRole.values()) {
                if (authentication.getAuthorities().stream()
                        .anyMatch(authority -> role.authority().equals(authority.getAuthority()))) {
                    roles.add(role);
                }
            }
            return roles;
        }

        static TrustOperationsService.StaffContext context(Authentication authentication) {
            if (authentication == null
                    || !(authentication.getPrincipal() instanceof dev.audiobook.platform.identity.ListenerPrincipal principal)) {
                throw new TrustOperationsAccessDeniedException();
            }
            return new TrustOperationsService.StaffContext(
                    principal.listenerId(), roles(authentication), principal.authenticatedAt());
        }
    }
}
