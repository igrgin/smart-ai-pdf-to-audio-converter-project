package dev.audiobook.platform.identity.internal.oidc;

import dev.audiobook.platform.identity.SignInProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@RequiredArgsConstructor
public final class BrokerIdentity {

    static final String EXTERNAL_ISSUER_CLAIM = "folio_external_issuer";
    static final String EXTERNAL_SUBJECT_CLAIM = "folio_external_subject";

    private final URI brokerIssuer;
    private final Duration freshAuthenticationMaxAge;
    private final Clock clock;

    ExternalIdentity from(SignInProvider provider, OidcUser user) {
        Map<String, Object> claims = user.getClaims();
        if (!brokerIssuer.toString().equals(String.valueOf(claims.get("iss")))) {
            throw new BrokerAuthenticationException();
        }
        requireFreshTotp(claims);

        String brokerSubject = requiredString(claims, "sub");
        String projectedIssuer = optionalString(claims, EXTERNAL_ISSUER_CLAIM);
        String projectedSubject = optionalString(claims, EXTERNAL_SUBJECT_CLAIM);
        if ((projectedIssuer == null) != (projectedSubject == null)) {
            throw new BrokerAuthenticationException();
        }

        URI issuer = projectedIssuer == null ? brokerIssuer : parseIssuer(projectedIssuer);
        String subject = projectedSubject == null ? brokerSubject : projectedSubject;
        return new ExternalIdentity(
                issuer,
                subject,
                provider,
                optionalString(claims, "email"),
                optionalString(claims, "name"));
    }

    private void requireFreshTotp(Map<String, Object> claims) {
        Object methodsClaim = claims.get("amr");
        if (!(methodsClaim instanceof Collection<?> methods)
                || !methods.containsAll(List.of("mfa", "otp"))) {
            throw new BrokerAuthenticationException();
        }

        Instant authenticatedAt = instantClaim(claims.get("auth_time"));
        Instant now = clock.instant();
        if (authenticatedAt == null
                || authenticatedAt.isBefore(now.minus(freshAuthenticationMaxAge))
                || authenticatedAt.isAfter(now.plusSeconds(30))) {
            throw new BrokerAuthenticationException();
        }
    }

    private static Instant instantClaim(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        return null;
    }

    private static String requiredString(Map<String, Object> claims, String name) {
        String value = optionalString(claims, name);
        if (value == null || value.isBlank()) {
            throw new BrokerAuthenticationException();
        }
        return value;
    }

    private static String optionalString(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static URI parseIssuer(String value) {
        try {
            URI issuer = URI.create(value);
            if (!"https".equalsIgnoreCase(issuer.getScheme()) || issuer.getHost() == null) {
                throw new BrokerAuthenticationException();
            }
            return issuer;
        } catch (IllegalArgumentException invalid) {
            throw new BrokerAuthenticationException();
        }
    }
}
