package dev.audiobook.platform.identity.internal.session;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.internal.IdentitySecurityProperties;
import dev.audiobook.platform.identity.internal.linking.IdentityLinkCeremony;
import dev.audiobook.platform.identity.SignInProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class IdentitySessionController {

    private final IdentitySecurityProperties properties;
    private final Clock clock;

    @GetMapping("/session")
    public ResponseEntity<SessionView> session(Authentication authentication, CsrfToken csrfToken) {
        ListenerView listener = principal(authentication) == null ? null : ListenerView.from(principal(authentication));
        StaffView staff = StaffView.from(authentication);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SessionView(listener != null, listener, staff, new CsrfView(
                        csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken())));
    }

    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> providers() {
        List<ProviderView> providers = Arrays.stream(SignInProvider.values())
                .map(ProviderView::from)
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("providers", providers));
    }

    @PostMapping("/links/{provider}")
    public ResponseEntity<?> link(
            @PathVariable String provider,
            Authentication authentication,
            HttpSession httpSession,
            @RequestHeader(value = "Accept", required = false) String accept) {
        ListenerPrincipal principal = principal(authentication);
        SignInProvider requestedProvider;
        try {
            requestedProvider = SignInProvider.fromRegistrationId(provider);
        } catch (IllegalArgumentException invalidProvider) {
            return ResponseEntity.notFound().build();
        }

        if (principal.authenticatedAt().isBefore(clock.instant().minus(properties.freshAuthenticationMaxAge()))) {
            httpSession.setAttribute(
                    IdentityLinkCeremony.SESSION_ATTRIBUTE,
                    IdentityLinkCeremony.awaitingCurrent(
                            principal.listenerId(), principal.currentProvider(), requestedProvider));
            if (accept != null && accept.contains("text/html")) {
                return ResponseEntity.status(HttpStatus.SEE_OTHER)
                        .location(URI.create(authorizationPath(principal.currentProvider())))
                        .build();
            }
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).body(Map.of(
                    "reauthenticationRequired", true,
                    "authorizationPath", authorizationPath(principal.currentProvider())));
        }

        httpSession.setAttribute(
                IdentityLinkCeremony.SESSION_ATTRIBUTE,
                IdentityLinkCeremony.awaitingTarget(
                        principal.listenerId(), principal.currentProvider(), requestedProvider));
        if (accept != null && accept.contains("text/html")) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(authorizationPath(requestedProvider)))
                    .build();
        }
        return ResponseEntity.accepted().body(Map.of("authorizationPath", authorizationPath(requestedProvider)));
    }

    @PostMapping("/recovery")
    public ResponseEntity<Void> recovery(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(properties.recoveryUri()).build();
    }

    private static String authorizationPath(SignInProvider provider) {
        return "/oauth2/authorization/" + provider.name().toLowerCase(Locale.ROOT);
    }

    private static ListenerPrincipal principal(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof ListenerPrincipal listener
                ? listener
                : null;
    }

    public record SessionView(boolean authenticated, ListenerView listener, StaffView staff, CsrfView csrf) {
    }

    public record ListenerView(String displayName, String contactEmail, List<String> signInMethods) {
        static ListenerView from(ListenerPrincipal principal) {
            return new ListenerView(
                    principal.displayName(),
                    principal.contactEmail(),
                    principal.providers().stream().map(provider -> provider.name().toLowerCase(Locale.ROOT)).sorted().toList());
        }
    }

    public record StaffView(List<String> roles) {
        private static final Set<String> STAFF_AUTHORITIES = Set.of(
                "ROLE_SUPPORT",
                "ROLE_RELIABILITY",
                "ROLE_ENTITLEMENT",
                "ROLE_VOICE",
                "ROLE_INCIDENT_RESPONDER",
                "ROLE_SECURITY_REVIEWER");

        static StaffView from(Authentication authentication) {
            if (authentication == null) {
                return null;
            }
            List<String> roles = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .filter(STAFF_AUTHORITIES::contains)
                    .map(authority -> authority.substring("ROLE_".length()))
                    .sorted()
                    .toList();
            return roles.isEmpty() ? null : new StaffView(roles);
        }
    }

    public record CsrfView(String headerName, String parameterName, String token) {
    }

    public record ProviderView(String id, String label, String authorizationPath) {
        static ProviderView from(SignInProvider provider) {
            String id = provider.name().toLowerCase(Locale.ROOT);
            String label = id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
            return new ProviderView(id, label, IdentitySessionController.authorizationPath(provider));
        }
    }
}
