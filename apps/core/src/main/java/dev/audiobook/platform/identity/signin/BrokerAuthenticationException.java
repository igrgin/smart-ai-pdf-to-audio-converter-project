package dev.audiobook.platform.identity.signin;

import dev.audiobook.platform.identity.signin.service.*;

import org.springframework.security.core.AuthenticationException;

public final class BrokerAuthenticationException extends AuthenticationException {

    public BrokerAuthenticationException() {
        super("Broker authentication did not satisfy the application policy");
    }
}
