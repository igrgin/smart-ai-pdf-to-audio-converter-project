package dev.audiobook.platform.trustoperations;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.trustoperations.service.*;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.CaseType;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.OpenCaseRequest;
import dev.audiobook.platform.trustoperations.service.TrustOperationsService.PrivilegedAction;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support-cases")
@RequiredArgsConstructor
public class ListenerSupportCaseController {

    private final JdbcTemplate jdbcTemplate;
    private final TrustOperationsService trustOperationsService;
    private final Clock identityClock;

    @PostMapping
    public ResponseEntity<TrustOperationsService.OperationsCase> requestSupport(
            @RequestBody SupportCaseRequest request,
            @RequestHeader("Idempotency-Key") String operationKey,
            Authentication authentication) {
        ListenerPrincipal listener = listener(authentication);
        Integer owned =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM library.private_audiobook WHERE audiobook_id = ? AND"
                                + " listener_id = ?",
                        Integer.class,
                        request.audiobookId(),
                        listener.listenerId());
        if (owned == null || owned != 1) {
            throw new TrustOperationsAccessDeniedException();
        }
        var operationsCase =
                trustOperationsService.openCase(
                        new OpenCaseRequest(
                                CaseType.SUPPORT,
                                listener.listenerId(),
                                "PRIVATE_AUDIOBOOK",
                                request.audiobookId(),
                                "PLAYBACK_SUPPORT_REQUESTED",
                                "PLAYBACK_REMAINS_UNAVAILABLE",
                                identityClock.instant().plusSeconds(24 * 3600),
                                60,
                                70,
                                Set.of(PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                                "listener-support:" + operationKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(operationsCase);
    }

    private static ListenerPrincipal listener(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof ListenerPrincipal listener)) {
            throw new TrustOperationsAccessDeniedException();
        }
        return listener;
    }

    record SupportCaseRequest(UUID audiobookId) {}
}
