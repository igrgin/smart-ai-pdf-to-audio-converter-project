package dev.audiobook.platform.admission.internal.inspection.intake;

import dev.audiobook.platform.admission.internal.AdmissionProperties;

import com.google.auth.oauth2.TokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("prod")
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class GooglePubSubPushAuthenticatorImpl implements PubSubPushAuthenticator {

    private final TokenVerifier verifier;
    private final String expectedServiceAccount;

    public GooglePubSubPushAuthenticatorImpl(AdmissionProperties properties) {
        verifier = TokenVerifier.newBuilder()
                .setAudience(properties.pushAudience())
                .setIssuer("https://accounts.google.com")
                .build();
        expectedServiceAccount = properties.pushServiceAccount();
    }

    @Override
    public boolean authentic(String token) {
        try {
            JsonWebSignature verified = verifier.verify(token);
            Object email = verified.getPayload().get("email");
            Object emailVerified = verified.getPayload().get("email_verified");
            return expectedServiceAccount.equals(email)
                    && (Boolean.TRUE.equals(emailVerified) || "true".equals(emailVerified));
        } catch (TokenVerifier.VerificationException exception) {
            return false;
        }
    }
}
