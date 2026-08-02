package dev.audiobook.platform.identity.signin.service;

import dev.audiobook.platform.identity.signin.*;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public interface BrokerOidcUserService extends OAuth2UserService<OidcUserRequest, OidcUser> {}
