package dev.audiobook.platform.offline.internal;

import dev.audiobook.platform.offline.internal.OfflineAccessProperties;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;

final class TestOfflineSigningKeys {

    private TestOfflineSigningKeys() {
    }

    static OfflineAccessProperties properties(Duration authorizationValidity, int chunkBytes) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var keyPair = generator.generateKeyPair();
            return new OfflineAccessProperties(
                    authorizationValidity,
                    chunkBytes,
                    Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()),
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                    null);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Test Offline Copy signing keys could not be generated", exception);
        }
    }
}
