package dev.audiobook.platform.identity;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@RequiredArgsConstructor
final class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final ListenerIdentityService listenerIdentityService;
    private final BrokerIdentity brokerIdentity;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                || !(oauth.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new BrokerAuthenticationException();
        }
        SignInProvider provider = SignInProvider.fromRegistrationId(oauth.getAuthorizedClientRegistrationId());
        ExternalIdentity externalIdentity = brokerIdentity.from(provider, oidcUser);
        HttpSession httpSession = request.getSession(true);

        Object pendingListener = httpSession.getAttribute(IdentityLinkCeremony.LISTENER_ID);
        Object pendingProvider = httpSession.getAttribute(IdentityLinkCeremony.PROVIDER);
        ListenerSession listener;
        if (pendingListener instanceof UUID listenerId && provider.name().equals(pendingProvider)) {
            listener = listenerIdentityService.link(listenerId, externalIdentity);
        } else if (pendingListener == null && pendingProvider == null) {
            listener = listenerIdentityService.establish(externalIdentity);
        } else {
            throw new BrokerAuthenticationException();
        }

        httpSession.removeAttribute(IdentityLinkCeremony.LISTENER_ID);
        httpSession.removeAttribute(IdentityLinkCeremony.PROVIDER);
        request.changeSessionId();
        httpSession.setAttribute(SessionLifecycleFilter.LAST_ROTATION, clock.millis());

        Instant authenticatedAt = authenticatedAt(oidcUser);
        ListenerPrincipal principal = new ListenerPrincipal(
                listener.listenerId(),
                listener.displayName(),
                listener.contactEmail(),
                listener.providers(),
                provider,
                authenticatedAt);
        UsernamePasswordAuthenticationToken listenerAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(listenerAuthentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        response.sendRedirect("/");
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
