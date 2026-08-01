package dev.audiobook.platform.offline.internal;

import dev.audiobook.platform.offline.OfflineAccessProperties;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !test & !itest")
final class LocalOfflineSignatureBoundary implements OfflineSignatureBoundary {

    private final PrivateKey privateKey;
    private final PublicKey verificationKey;
    private final String encodedPublicKey;

    LocalOfflineSignatureBoundary(OfflineAccessProperties properties) {
        if (properties.signingPrivateKey() == null || properties.signingPrivateKey().isBlank()
                || properties.signingPublicKey() == null || properties.signingPublicKey().isBlank()) {
            throw new IllegalArgumentException("Local Offline Copy signing keys are required");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            this.privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(properties.signingPrivateKey())));
            this.verificationKey = keyFactory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(properties.signingPublicKey())));
            this.encodedPublicKey = properties.signingPublicKey();
            verifyKeyPair();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Local Offline Copy signing key pair is invalid", exception);
        }
    }

    @Override
    public String publicKey() {
        return encodedPublicKey;
    }

    @Override
    public byte[] sign(byte[] payload) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(privateKey);
            signer.update(payload);
            return signer.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("Offline Copy authorization could not be signed", exception);
        }
    }

    private void verifyKeyPair() throws Exception {
        byte[] probe = "folio-offline-signing-key-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(verificationKey);
        verifier.update(probe);
        if (!verifier.verify(sign(probe))) {
            throw new IllegalArgumentException("Offline Copy signing keys do not form a pair");
        }
    }
}
