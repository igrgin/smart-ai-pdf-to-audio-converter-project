package dev.audiobook.platform.admission;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!prod")
public class LocalPubSubPushAuthenticatorImpl implements PubSubPushAuthenticator {

    @Override
    public boolean authentic(String token) {
        return false;
    }
}
