package dev.audiobook.platform.offline.internal;

import dev.audiobook.platform.offline.OfflineAccessService;

interface OfflineAuthorizationSigner {

    OfflineAccessService.SignedAuthorization sign(OfflineAccessService.AuthorizationClaims claims);

    OfflineAccessService.SignedAuthorization restore(
            OfflineAccessService.AuthorizationClaims claims,
            String payload,
            String signature);
}
