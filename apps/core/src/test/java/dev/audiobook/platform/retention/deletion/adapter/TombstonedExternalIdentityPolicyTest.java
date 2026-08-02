package dev.audiobook.platform.retention.deletion.adapter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.identity.signin.BrokerAuthenticationException;
import dev.audiobook.platform.identity.signin.ExternalIdentity;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.deletion.persistence.ExternalIdentityTombstonePersistence;

import org.junit.jupiter.api.Test;

class TombstonedExternalIdentityPolicyTest {

    private final ExternalIdentityTombstonePersistence persistence =
            mock(ExternalIdentityTombstonePersistence.class);
    private final RetentionDigest digest = mock(RetentionDigest.class);
    private final TombstonedExternalIdentityPolicy policy =
            new TombstonedExternalIdentityPolicy(persistence, digest);
    private final ExternalIdentity identity =
            new ExternalIdentity(
                    java.net.URI.create("https://issuer.example"),
                    "subject",
                    SignInProvider.GOOGLE,
                    "listener@example.test",
                    "Listener");

    @Test
    void blocksAnExternalIdentityWhoseOpaqueDigestWasTombstoned() {
        when(digest.digest(anyString(), anyString())).thenReturn("a".repeat(64));
        when(persistence.exists("a".repeat(64))).thenReturn(true);

        assertThatThrownBy(() -> policy.requireAllowed(identity))
                .isInstanceOf(BrokerAuthenticationException.class);
    }

    @Test
    void allowsAnExternalIdentityWithoutATombstone() {
        when(digest.digest(anyString(), anyString())).thenReturn("b".repeat(64));

        assertThatCode(() -> policy.requireAllowed(identity)).doesNotThrowAnyException();
    }
}
