package dev.audiobook.platform.identity.signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.identity.IdentitySecurityProperties;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.listener.service.ListenerIdentityService;
import dev.audiobook.platform.identity.session.ListenerSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.SecurityContextRepository;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class OidcLoginSuccessHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
    private static final URI BROKER_ISSUER = URI.create("https://login.eu.example");
    private static final UUID OPERATOR_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000099");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowlistedListenerReceivesOperatorAuthorityAfterBrokerAuthentication() throws Exception {
        Authentication authentication = authenticate(Set.of(OPERATOR_ID));

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_LISTENER", "ROLE_OPERATOR");
    }

    @Test
    void ordinaryListenerDoesNotReceiveOperatorAuthorityAfterBrokerAuthentication()
            throws Exception {
        Authentication authentication = authenticate(Set.of());

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_LISTENER");
    }

    @Test
    void namedStaffReceivesOnlyExplicitlyConfiguredTrustOperationsRoles() throws Exception {
        Authentication authentication =
                authenticate(
                        Set.of(),
                        Set.of(OPERATOR_ID),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(OPERATOR_ID),
                        Set.of());

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_LISTENER", "ROLE_SUPPORT", "ROLE_INCIDENT_RESPONDER")
                .doesNotContain("ROLE_OPERATOR");
    }

    private Authentication authenticate(Set<UUID> operatorListenerIds) throws Exception {
        return authenticate(
                operatorListenerIds, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private Authentication authenticate(
            Set<UUID> operatorListenerIds,
            Set<UUID> supportStaffListenerIds,
            Set<UUID> reliabilityStaffListenerIds,
            Set<UUID> entitlementStaffListenerIds,
            Set<UUID> voiceStaffListenerIds,
            Set<UUID> incidentResponderListenerIds,
            Set<UUID> securityReviewerListenerIds)
            throws Exception {
        ListenerIdentityService identityService = mock(ListenerIdentityService.class);
        SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
        ExternalIdentity externalIdentity =
                new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "operator-subject",
                        SignInProvider.GOOGLE,
                        "operator@example.test",
                        "Operator");
        when(identityService.establish(any()))
                .thenReturn(
                        new ListenerSession(
                                OPERATOR_ID,
                                "Operator",
                                "operator@example.test",
                                Set.of(SignInProvider.GOOGLE)));
        IdentitySecurityProperties properties =
                new IdentitySecurityProperties(
                        URI.create("http://localhost:3000"),
                        BROKER_ISSUER,
                        URI.create("https://login.eu.example/ui/v2/login"),
                        Map.of(SignInProvider.GOOGLE, "google-idp"),
                        operatorListenerIds,
                        supportStaffListenerIds,
                        reliabilityStaffListenerIds,
                        entitlementStaffListenerIds,
                        voiceStaffListenerIds,
                        incidentResponderListenerIds,
                        securityReviewerListenerIds,
                        false,
                        Duration.ofMinutes(5),
                        Duration.ofHours(8),
                        Duration.ofMinutes(10));
        OidcLoginSuccessHandler handler =
                new OidcLoginSuccessHandler(
                        identityService,
                        new BrokerIdentity(BROKER_ISSUER, Duration.ofMinutes(5), fixedClock()),
                        properties,
                        contextRepository,
                        fixedClock());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                request, response, new OAuth2AuthenticationToken(user(), List.of(), "google"));

        ArgumentCaptor<SecurityContext> context = ArgumentCaptor.forClass(SecurityContext.class);
        verify(contextRepository).saveContext(context.capture(), any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        verify(identityService).establish(externalIdentity);
        return context.getValue().getAuthentication();
    }

    private static DefaultOidcUser user() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", BROKER_ISSUER.toString());
        claims.put("sub", "zitadel-operator");
        claims.put("aud", List.of("folio-client"));
        claims.put("amr", List.of("mfa", "otp"));
        claims.put("auth_time", NOW.minusSeconds(30));
        claims.put("folio_external_issuer", "https://accounts.google.com");
        claims.put("folio_external_subject", "operator-subject");
        claims.put("email", "operator@example.test");
        claims.put("name", "Operator");
        OidcIdToken idToken =
                new OidcIdToken(
                        "server-side-token", NOW.minusSeconds(30), NOW.plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(), idToken);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
