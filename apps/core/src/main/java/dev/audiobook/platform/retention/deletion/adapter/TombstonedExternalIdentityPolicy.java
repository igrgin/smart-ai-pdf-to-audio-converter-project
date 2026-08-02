package dev.audiobook.platform.retention.deletion.adapter;

import dev.audiobook.platform.identity.signin.BrokerAuthenticationException;
import dev.audiobook.platform.identity.signin.DeletedExternalIdentityPolicy;
import dev.audiobook.platform.identity.signin.ExternalIdentity;
import dev.audiobook.platform.retention.RetentionDigest;
import dev.audiobook.platform.retention.deletion.persistence.ExternalIdentityTombstonePersistence;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TombstonedExternalIdentityPolicy implements DeletedExternalIdentityPolicy {

    private final ExternalIdentityTombstonePersistence persistence;
    private final RetentionDigest retentionDigest;

    @Override
    public void requireAllowed(ExternalIdentity externalIdentity) {
        String identityDigest =
                retentionDigest.digest(
                        "external-identity",
                        externalIdentity.issuer() + "\n" + externalIdentity.subject());
        if (persistence.exists(identityDigest)) {
            throw new BrokerAuthenticationException();
        }
    }
}
