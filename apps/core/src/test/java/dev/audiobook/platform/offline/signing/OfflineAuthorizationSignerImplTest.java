package dev.audiobook.platform.offline.signing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.offline.authorization.service.OfflineAccessService;

import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

class OfflineAuthorizationSignerImplTest {

    @Test
    void signsCanonicalBoundClaimsWithTheConfiguredNonProductionKeyPair() throws Exception {
        var properties = TestOfflineSigningKeys.properties(Duration.ofDays(30), 4);
        var signer =
                new OfflineAuthorizationSignerImpl(new LocalOfflineSignatureBoundary(properties));
        var claims =
                new OfflineAccessService.AuthorizationClaims(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        7,
                        "OFFLINE_PLAYBACK",
                        Instant.parse("2026-08-01T12:00:00Z"),
                        Instant.parse("2026-08-31T12:00:00Z"));

        var signed = signer.sign(claims);

        assertThat(signed.claims()).isEqualTo(claims);
        assertThat(signed.algorithm()).isEqualTo("ES256");
        assertThat(signed.publicKey()).isEqualTo(properties.signingPublicKey());
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(
                KeyFactory.getInstance("EC")
                        .generatePublic(
                                new X509EncodedKeySpec(
                                        Base64.getDecoder()
                                                .decode(properties.signingPublicKey()))));
        verifier.update(Base64.getUrlDecoder().decode(signed.payload()));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(signed.signature()))).isTrue();
    }
}
