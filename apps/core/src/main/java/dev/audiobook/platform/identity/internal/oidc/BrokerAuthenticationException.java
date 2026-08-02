package dev.audiobook.platform.identity.internal.oidc;

import org.springframework.security.core.AuthenticationException;

public final class BrokerAuthenticationException extends AuthenticationException {

    public BrokerAuthenticationException() {
        super("Broker authentication did not satisfy the application policy");
    }
}
