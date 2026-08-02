package dev.audiobook.platform.offline.signing;

import dev.audiobook.platform.offline.authorization.service.OfflineAccessService;

public interface OfflineAuthorizationSigner {

    OfflineAccessService.SignedAuthorization sign(OfflineAccessService.AuthorizationClaims claims);

    OfflineAccessService.SignedAuthorization restore(
            OfflineAccessService.AuthorizationClaims claims, String payload, String signature);
}
