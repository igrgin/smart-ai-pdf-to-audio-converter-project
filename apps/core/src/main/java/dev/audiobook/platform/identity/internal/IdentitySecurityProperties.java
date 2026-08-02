package dev.audiobook.platform.identity.internal;

import dev.audiobook.platform.identity.SignInProvider;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.identity")
public record IdentitySecurityProperties(
        URI allowedOrigin,
        URI brokerIssuer,
        URI recoveryUri,
        Map<SignInProvider, String> brokerProviderIds,
        Set<UUID> operatorListenerIds,
        Set<UUID> supportStaffListenerIds,
        Set<UUID> reliabilityStaffListenerIds,
        Set<UUID> entitlementStaffListenerIds,
        Set<UUID> voiceStaffListenerIds,
        Set<UUID> incidentResponderListenerIds,
        Set<UUID> securityReviewerListenerIds,
        boolean secureSessionCookie,
        Duration freshAuthenticationMaxAge,
        Duration sessionAbsoluteTimeout,
        Duration sessionRotationInterval) {

    public IdentitySecurityProperties {
        brokerProviderIds = Map.copyOf(brokerProviderIds);
        operatorListenerIds = operatorListenerIds == null ? Set.of() : Set.copyOf(operatorListenerIds);
        supportStaffListenerIds = immutable(supportStaffListenerIds);
        reliabilityStaffListenerIds = immutable(reliabilityStaffListenerIds);
        entitlementStaffListenerIds = immutable(entitlementStaffListenerIds);
        voiceStaffListenerIds = immutable(voiceStaffListenerIds);
        incidentResponderListenerIds = immutable(incidentResponderListenerIds);
        securityReviewerListenerIds = immutable(securityReviewerListenerIds);
    }

    public String brokerProviderId(SignInProvider provider) {
        String providerId = brokerProviderIds.get(provider);
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalStateException("Missing ZITADEL broker provider ID for " + provider.name());
        }
        return providerId;
    }

    public boolean isOperator(UUID listenerId) {
        return operatorListenerIds.contains(listenerId);
    }

    public java.util.List<String> staffAuthorities(UUID listenerId) {
        java.util.ArrayList<String> authorities = new java.util.ArrayList<>();
        add(authorities, supportStaffListenerIds, listenerId, "ROLE_SUPPORT");
        add(authorities, reliabilityStaffListenerIds, listenerId, "ROLE_RELIABILITY");
        add(authorities, entitlementStaffListenerIds, listenerId, "ROLE_ENTITLEMENT");
        add(authorities, voiceStaffListenerIds, listenerId, "ROLE_VOICE");
        add(authorities, incidentResponderListenerIds, listenerId, "ROLE_INCIDENT_RESPONDER");
        add(authorities, securityReviewerListenerIds, listenerId, "ROLE_SECURITY_REVIEWER");
        return java.util.List.copyOf(authorities);
    }

    private static Set<UUID> immutable(Set<UUID> identifiers) {
        return identifiers == null ? Set.of() : Set.copyOf(identifiers);
    }

    private static void add(java.util.List<String> authorities, Set<UUID> identifiers, UUID listenerId, String authority) {
        if (identifiers.contains(listenerId)) {
            authorities.add(authority);
        }
    }
}
