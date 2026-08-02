package dev.audiobook.platform.identity.signin;

import dev.audiobook.platform.identity.IdentitySecurityProperties;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.linking.IdentityLinkCeremony;
import dev.audiobook.platform.identity.linking.IdentityLinkConflictException;
import dev.audiobook.platform.identity.listener.service.ListenerIdentityService;
import dev.audiobook.platform.identity.session.ListenerSession;
import dev.audiobook.platform.identity.session.SessionLifecycleFilter;
import dev.audiobook.platform.identity.signin.service.*;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public final class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final ListenerIdentityService listenerIdentityService;
    private final BrokerIdentity brokerIdentity;
    private final IdentitySecurityProperties securityProperties;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        try {
            completeAuthentication(request, response, authentication);
        } catch (BrokerAuthenticationException | IdentityLinkConflictException denied) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendRedirect("/?sign-in=failed");
        }
    }

    private void completeAuthentication(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                || !(oauth.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new BrokerAuthenticationException();
        }
        SignInProvider provider =
                SignInProvider.fromRegistrationId(oauth.getAuthorizedClientRegistrationId());
        ExternalIdentity externalIdentity = brokerIdentity.from(provider, oidcUser);
        HttpSession httpSession = request.getSession(true);

        Object pendingLink = httpSession.getAttribute(IdentityLinkCeremony.SESSION_ATTRIBUTE);
        ListenerSession listener;
        String redirect = "/";
        if (pendingLink instanceof IdentityLinkCeremony ceremony
                && ceremony.stage() == IdentityLinkCeremony.Stage.AWAITING_CURRENT
                && provider == ceremony.currentProvider()) {
            listener = listenerIdentityService.establish(externalIdentity);
            if (!listener.listenerId().equals(ceremony.listenerId())) {
                throw new BrokerAuthenticationException();
            }
            httpSession.setAttribute(
                    IdentityLinkCeremony.SESSION_ATTRIBUTE, ceremony.afterCurrentAuthentication());
            redirect = authorizationPath(ceremony.targetProvider());
        } else if (pendingLink instanceof IdentityLinkCeremony ceremony
                && ceremony.stage() == IdentityLinkCeremony.Stage.AWAITING_TARGET
                && provider == ceremony.targetProvider()) {
            listener = listenerIdentityService.link(ceremony.listenerId(), externalIdentity);
            httpSession.removeAttribute(IdentityLinkCeremony.SESSION_ATTRIBUTE);
        } else if (pendingLink == null) {
            listener = listenerIdentityService.establish(externalIdentity);
        } else {
            throw new BrokerAuthenticationException();
        }

        request.changeSessionId();
        httpSession.setAttribute(SessionLifecycleFilter.LAST_ROTATION, clock.millis());

        Instant authenticatedAt = authenticatedAt(oidcUser);
        ListenerPrincipal principal =
                new ListenerPrincipal(
                        listener.listenerId(),
                        listener.displayName(),
                        listener.contactEmail(),
                        listener.providers(),
                        provider,
                        authenticatedAt);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_LISTENER"));
        if (securityProperties.isOperator(listener.listenerId())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        }
        securityProperties.staffAuthorities(listener.listenerId()).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        UsernamePasswordAuthenticationToken listenerAuthentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(listenerAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        response.sendRedirect(redirect);
    }

    private static String authorizationPath(SignInProvider provider) {
        return "/oauth2/authorization/" + provider.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Instant authenticatedAt(OidcUser user) {
        Object value = user.getClaims().get("auth_time");
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        throw new BrokerAuthenticationException();
    }
}
