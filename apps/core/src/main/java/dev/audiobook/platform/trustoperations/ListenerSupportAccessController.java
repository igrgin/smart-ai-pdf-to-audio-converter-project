package dev.audiobook.platform.trustoperations;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.trustoperations.delegated.DelegatedAccessRequestWorkflow;
import dev.audiobook.platform.trustoperations.service.*;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService;

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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support-access-grants")
@RequiredArgsConstructor
public class ListenerSupportAccessController {

    private final TrustOperationsService trustOperationsService;
    private final DelegatedAccessRequestWorkflow delegatedAccessRequests;

    @GetMapping
    public ResponseEntity<ListenerAccessView> access(Authentication authentication) {
        UUID listenerId = listener(authentication).listenerId();
        TrustOperationsService.ListenerAccessSummary summary =
                trustOperationsService.listenerAccess(listenerId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new ListenerAccessView(
                                summary.grants(),
                                summary.notifications(),
                                delegatedAccessRequests.pending(listenerId)));
    }

    @PostMapping
    public ResponseEntity<TrustOperationsService.DelegatedAccessGrant> approve(
            @RequestBody ApprovalRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        ListenerPrincipal listener = listener(authentication);
        TrustOperationsService.DelegatedAccessGrant grant =
                delegatedAccessRequests.approve(
                        listener.listenerId(), request.requestId(), operationKey);
        HttpStatus status = grant.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(grant);
    }

    @PostMapping("/{grantId}/revocation")
    public ResponseEntity<TrustOperationsService.DelegatedAccessGrant> revoke(
            @PathVariable UUID grantId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        TrustOperationsService.DelegatedAccessGrant grant =
                trustOperationsService.revokeDelegatedAccess(
                        new TrustOperationsService.RevokeDelegatedAccessCommand(
                                listener(authentication).listenerId(),
                                grantId,
                                expectedVersion(ifMatch),
                                operationKey));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grant);
    }

    private static ListenerPrincipal listener(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof ListenerPrincipal listener)) {
            throw new TrustOperationsAccessDeniedException();
        }
        return listener;
    }

    static long expectedVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) {
            throw new TrustOperationsPreconditionException();
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException invalid) {
            throw new TrustOperationsPreconditionException();
        }
    }

    public record ApprovalRequest(UUID requestId) {}

    record ListenerAccessView(
            java.util.List<TrustOperationsService.DelegatedAccessGrant> grants,
            java.util.List<TrustOperationsService.ListenerNotification> notifications,
            java.util.List<DelegatedAccessRequestWorkflow.PendingAccessRequest> pendingRequests) {}
}
