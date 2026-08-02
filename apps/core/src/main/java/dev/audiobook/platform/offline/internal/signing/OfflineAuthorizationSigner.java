package dev.audiobook.platform.offline.internal.signing;

import dev.audiobook.platform.offline.OfflineAccessService;

public interface OfflineAuthorizationSigner {

    OfflineAccessService.SignedAuthorization sign(OfflineAccessService.AuthorizationClaims claims);

    OfflineAccessService.SignedAuthorization restore(
            OfflineAccessService.AuthorizationClaims claims,
            String payload,
            String signature);
}
