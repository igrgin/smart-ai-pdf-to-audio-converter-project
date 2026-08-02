package dev.audiobook.platform.offline.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import dev.audiobook.platform.offline.internal.OfflineAccessProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32C;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
final class GoogleKmsOfflineSignatureBoundary implements OfflineSignatureBoundary {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final String KMS_ENDPOINT = "https://cloudkms.googleapis.com/v1/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final GoogleCredentials credentials;
    private final String signingKeyVersion;
    private volatile String encodedPublicKey;

    GoogleKmsOfflineSignatureBoundary(OfflineAccessProperties properties) throws IOException {
        if (properties.signingKeyVersion() == null
                || !properties.signingKeyVersion().matches(
                        "projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+/cryptoKeyVersions/[0-9]+")) {
            throw new IllegalArgumentException("Cloud KMS Offline Copy signing key version is invalid");
        }
        this.signingKeyVersion = properties.signingKeyVersion();
        this.credentials = GoogleCredentials.getApplicationDefault().createScoped(CLOUD_PLATFORM_SCOPE);
    }

    @Override
    public String publicKey() {
        String available = encodedPublicKey;
        if (available != null) return available;
        synchronized (this) {
            if (encodedPublicKey == null) encodedPublicKey = fetchPublicKey();
            return encodedPublicKey;
        }
    }

    @Override
    public byte[] sign(byte[] payload) {
        try {
            byte[] digestBytes = MessageDigest.getInstance("SHA-256").digest(payload);
            String digest = Base64.getEncoder().encodeToString(digestBytes);
            JsonNode response = request(
                    URI.create(KMS_ENDPOINT + signingKeyVersion + ":asymmetricSign"),
                    "POST",
                    "{\"digest\":{\"sha256\":\"" + digest + "\"},"
                            + "\"digestCrc32c\":\"" + crc32c(digestBytes) + "\"}");
            byte[] derSignature = Base64.getDecoder().decode(requiredText(response, "signature"));
            if (!response.path("verifiedDigestCrc32c").asBoolean()
                    || !signingKeyVersion.equals(requiredText(response, "name"))
                    || crc32c(derSignature) != Long.parseLong(requiredText(response, "signatureCrc32c"))) {
                throw new IllegalStateException("Cloud KMS Offline Copy signature integrity check failed");
            }
            return derToP1363(derSignature);
        } catch (Exception exception) {
            throw new IllegalStateException("Cloud KMS could not sign Offline Copy authorization", exception);
        }
    }

    private String fetchPublicKey() {
        try {
            JsonNode response = request(URI.create(KMS_ENDPOINT + signingKeyVersion + "/publicKey"), "GET", null);
            if (!"EC_SIGN_P256_SHA256".equals(requiredText(response, "algorithm"))) {
                throw new IllegalStateException("Cloud KMS Offline Copy key must use EC_SIGN_P256_SHA256");
            }
            String pem = requiredText(response, "pem");
            if (!signingKeyVersion.equals(requiredText(response, "name"))
                    || crc32c(pem.getBytes(StandardCharsets.UTF_8))
                    != Long.parseLong(requiredText(response, "pemCrc32c"))) {
                throw new IllegalStateException("Cloud KMS Offline Copy public-key integrity check failed");
            }
            return pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
        } catch (Exception exception) {
            throw new IllegalStateException("Cloud KMS Offline Copy public key is unavailable", exception);
        }
    }

    private JsonNode request(URI uri, String method, String body) throws Exception {
        credentials.refreshIfExpired();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                .header("Accept", "application/json");
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Cloud KMS request failed with status " + response.statusCode());
        }
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static String requiredText(JsonNode response, String field) {
        String value = response.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Cloud KMS response is missing " + field);
        return value;
    }

    private static long crc32c(byte[] content) {
        CRC32C checksum = new CRC32C();
        checksum.update(content);
        return checksum.getValue();
    }

    static byte[] derToP1363(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("ECDSA signature is invalid");
        int index = der[1] < 0 ? 2 + (der[1] & 0x7f) : 2;
        if (index >= der.length || der[index++] != 0x02) throw new IllegalArgumentException("ECDSA signature is invalid");
        int rLength = der[index++] & 0xff;
        if (index + rLength >= der.length) throw new IllegalArgumentException("ECDSA signature is invalid");
        byte[] r = Arrays.copyOfRange(der, index, index + rLength);
        index += rLength;
        if (der[index++] != 0x02 || index >= der.length) throw new IllegalArgumentException("ECDSA signature is invalid");
        int sLength = der[index++] & 0xff;
        if (index + sLength != der.length) throw new IllegalArgumentException("ECDSA signature is invalid");
        byte[] s = Arrays.copyOfRange(der, index, index + sLength);
        byte[] signature = new byte[64];
        copyInteger(r, signature, 0);
        copyInteger(s, signature, 32);
        return signature;
    }

    private static void copyInteger(byte[] integer, byte[] destination, int offset) {
        int sourceOffset = integer.length > 32 && integer[0] == 0 ? 1 : 0;
        int length = integer.length - sourceOffset;
        if (length <= 0 || length > 32) throw new IllegalArgumentException("ECDSA integer is invalid");
        System.arraycopy(integer, sourceOffset, destination, offset + 32 - length, length);
    }
}
