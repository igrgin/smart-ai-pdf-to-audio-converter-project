package dev.audiobook.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class BrokerIdentityTest {

    private static final Instant NOW = Instant.parse("2026-07-31T19:00:00Z");
    private static final URI BROKER_ISSUER = URI.create("https://login.eu.example");
    private final BrokerIdentity brokerIdentity = new BrokerIdentity(
            BROKER_ISSUER,
            Duration.ofMinutes(5),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(SignInProvider.class)
    void acceptsFreshProviderAuthenticationWithBrokerEnforcedTotp(SignInProvider provider) {
        ExternalIdentity result = brokerIdentity.from(provider, user(Map.of(
                "amr", List.of("mfa", "otp"),
                "auth_time", NOW.minusSeconds(30),
                "folio_external_issuer", providerIssuer(provider),
                "folio_external_subject", provider.name().toLowerCase() + "-subject",
                "email", provider == SignInProvider.APPLE ? "relay@privaterelay.appleid.com" : "listener@example.test",
                "name", "A Listener")));

        assertThat(result.provider()).isEqualTo(provider);
        assertThat(result.issuer()).isEqualTo(URI.create(providerIssuer(provider)));
        assertThat(result.subject()).isEqualTo(provider.name().toLowerCase() + "-subject");
    }

    @Test
    void permitsMissingEmailAsContactMetadata() {
        ExternalIdentity result = brokerIdentity.from(SignInProvider.FACEBOOK, user(Map.of(
                "amr", List.of("mfa", "otp"),
                "auth_time", NOW,
                "name", "Listener")));

        assertThat(result.email()).isNull();
        assertThat(result.issuer()).isEqualTo(BROKER_ISSUER);
        assertThat(result.subject()).isEqualTo("zitadel-subject");
    }

    @Test
    void rejectsProviderResultWithoutTotpMfa() {
        assertThatThrownBy(() -> brokerIdentity.from(SignInProvider.GOOGLE, user(Map.of(
                "amr", List.of("pwd"),
                "auth_time", NOW))))
                .isInstanceOf(BrokerAuthenticationException.class);
    }

    @Test
    void rejectsMfaWithoutTheTotpMethod() {
        assertThatThrownBy(() -> brokerIdentity.from(SignInProvider.GOOGLE, user(Map.of(
                "amr", List.of("mfa"),
                "auth_time", NOW))))
                .isInstanceOf(BrokerAuthenticationException.class);
    }

    @Test
    void rejectsOtpWithoutTheBrokerMfaDecision() {
        assertThatThrownBy(() -> brokerIdentity.from(SignInProvider.GOOGLE, user(Map.of(
                "amr", List.of("otp"),
                "auth_time", NOW))))
                .isInstanceOf(BrokerAuthenticationException.class);
    }

    @Test
    void rejectsStaleAuthenticationForAnInteractiveCeremony() {
        assertThatThrownBy(() -> brokerIdentity.from(SignInProvider.APPLE, user(Map.of(
                "amr", List.of("mfa", "otp"),
                "auth_time", NOW.minus(Duration.ofMinutes(6))))))
                .isInstanceOf(BrokerAuthenticationException.class);
    }

    private static DefaultOidcUser user(Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", BROKER_ISSUER.toString());
        claims.put("sub", "zitadel-subject");
        claims.put("aud", List.of("folio-client"));
        claims.putAll(additionalClaims);
        OidcIdToken idToken = new OidcIdToken("server-side-token", NOW.minusSeconds(30), NOW.plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(), idToken);
    }

    private static String providerIssuer(SignInProvider provider) {
        return switch (provider) {
            case GOOGLE -> "https://accounts.google.com";
            case APPLE -> "https://appleid.apple.com";
            case FACEBOOK -> "https://www.facebook.com";
        };
    }
}
