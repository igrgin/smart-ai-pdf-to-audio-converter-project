package dev.audiobook.platform.offline.internal.authorization;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.offline.OfflineAccessService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audiobooks")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
final class OfflineAccessController {

    private final OfflineAccessService offlineAccessService;

    @PostMapping("/{audiobookId}/asset-versions/{assetVersionId}/offline-copy-authorizations")
    ResponseEntity<OfflineAccessService.OfflineCopyAuthorization> authorize(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID audiobookId,
            @PathVariable UUID assetVersionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody AuthorizationRequest request) {
        OfflineAccessService.OfflineCopyAuthorization authorization = offlineAccessService.issue(
                new OfflineAccessService.IssueAuthorization(
                        principal.listenerId(),
                        request.installationId(),
                        audiobookId,
                        assetVersionId,
                        idempotencyKey));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authorization);
    }

    record AuthorizationRequest(UUID installationId) {
    }
}
