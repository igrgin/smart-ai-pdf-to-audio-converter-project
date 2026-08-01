package dev.audiobook.platform.offline.internal;

import dev.audiobook.platform.offline.OfflineAccessService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
final class OfflineAuthorizationSignerImpl implements OfflineAuthorizationSigner {

    private static final String ALGORITHM = "ES256";
    private static final String KEY_ID = "offline-v1";

    private final OfflineSignatureBoundary signatureBoundary;

    @Override
    public OfflineAccessService.SignedAuthorization sign(OfflineAccessService.AuthorizationClaims claims) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(canonicalPayload(claims));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                signatureBoundary.sign(Base64.getUrlDecoder().decode(payload)));
        return restore(claims, payload, signature);
    }

    @Override
    public OfflineAccessService.SignedAuthorization restore(
            OfflineAccessService.AuthorizationClaims claims,
            String payload,
            String signature) {
        return new OfflineAccessService.SignedAuthorization(
                ALGORITHM, KEY_ID, signatureBoundary.publicKey(), payload, signature, claims);
    }

    private static byte[] canonicalPayload(OfflineAccessService.AuthorizationClaims claims) {
        String json = "{" +
                "\"listenerId\":\"" + claims.listenerId() + "\"," +
                "\"installationId\":\"" + claims.installationId() + "\"," +
                "\"audiobookId\":\"" + claims.audiobookId() + "\"," +
                "\"assetVersionId\":\"" + claims.assetVersionId() + "\"," +
                "\"authorizationGeneration\":" + claims.authorizationGeneration() + "," +
                "\"purpose\":\"" + claims.purpose() + "\"," +
                "\"issuedAt\":\"" + claims.issuedAt() + "\"," +
                "\"expiresAt\":\"" + claims.expiresAt() + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
