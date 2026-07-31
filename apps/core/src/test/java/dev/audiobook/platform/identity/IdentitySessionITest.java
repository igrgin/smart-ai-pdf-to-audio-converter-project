package dev.audiobook.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.PlatformApplication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("itest")
@Import({IdentitySessionITest.BrokerStubConfiguration.class, IdentitySessionITest.BrokerStubController.class})
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentitySessionITest {

    private static final String ORIGIN = "http://localhost:3000";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("audiobook")
            .withUsername("audiobook")
            .withPassword("integration-test-only");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void providerBrokerSessionsLinkingRecoveryAndCrossListenerDenialAreObservable() throws Exception {
        SessionClient first = client();
        String firstCsrf = csrf(first);
        String anonymousSession = first.sessionId();

        assertThat(broker(first, "google", "google-one", "same@example.test", "First Listener", "mfa otp").statusCode())
                .isEqualTo(204);
        assertThat(first.sessionId()).isNotEqualTo(anonymousSession);
        HttpResponse<String> firstLibrary = get(first, "/api/v1/library");
        assertThat(firstLibrary.statusCode()).isEqualTo(200);
        assertThat(firstLibrary.body())
                .contains("First Listener", "same@example.test", "google")
                .doesNotContain("google-one", "access_token", "id_token", "listenerId");

        SessionClient second = client();
        csrf(second);
        assertThat(broker(second, "facebook", "facebook-two", "same@example.test", "Second Listener", "mfa otp").statusCode())
                .isEqualTo(204);
        assertThat(get(second, "/api/v1/library").body())
                .contains("Second Listener", "facebook")
                .doesNotContain("First Listener", "google-one");

        String refreshedFirstCsrf = csrf(first);
        HttpResponse<String> startLink = apiPost(first, "/api/v1/auth/links/apple", refreshedFirstCsrf);
        assertThat(startLink.statusCode()).isEqualTo(202);
        assertThat(broker(first, "apple", "shared-apple", "relay@privaterelay.appleid.com", "First Listener", "mfa otp").statusCode())
                .isEqualTo(204);
        assertThat(get(first, "/api/v1/library").body()).contains("apple", "google");

        String secondCsrf = csrf(second);
        assertThat(apiPost(second, "/api/v1/auth/links/apple", secondCsrf).statusCode()).isEqualTo(202);
        HttpResponse<String> conflictingLink = broker(
                second, "apple", "shared-apple", null, "Second Listener", "mfa otp");
        assertThat(conflictingLink.statusCode()).isEqualTo(409);
        assertThat(conflictingLink.body()).doesNotContain("First Listener", "shared-apple", "same@example.test");

        for (String provider : List.of("google", "apple", "facebook")) {
            SessionClient rejected = client();
            csrf(rejected);
            assertThat(broker(rejected, provider, "no-mfa-" + provider, null, "Rejected", "pwd").statusCode())
                    .isEqualTo(401);
            assertThat(get(rejected, "/api/v1/library").statusCode()).isEqualTo(401);
        }

        String logoutCsrf = csrf(first);
        assertThat(apiPost(first, "/api/v1/auth/logout", logoutCsrf).statusCode()).isEqualTo(204);
        assertThat(get(first, "/api/v1/library").statusCode()).isEqualTo(401);

        String recoveryCsrf = csrf(second);
        HttpResponse<String> recovery = apiPost(second, "/api/v1/auth/recovery", recoveryCsrf);
        assertThat(recovery.statusCode()).isEqualTo(303);
        assertThat(recovery.headers().firstValue("location")).contains("https://login.eu.example/ui/v2/login");
        assertThat(get(second, "/api/v1/library").statusCode()).isEqualTo(401);
    }

    private String csrf(SessionClient client) throws IOException, InterruptedException {
        HttpResponse<String> response = get(client, "/api/v1/auth/session");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("csrf").path("token").asText();
    }

    private HttpResponse<String> broker(
            SessionClient client,
            String provider,
            String subject,
            String email,
            String displayName,
            String methods) throws IOException, InterruptedException {
        HttpRequest.Builder request = request("/__itest/broker/" + provider)
                .header("X-Test-Subject", subject)
                .header("X-Test-Name", displayName)
                .header("X-Test-Amr", methods)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (email != null) {
            request.header("X-Test-Email", email);
        }
        return client.http().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> apiPost(SessionClient client, String path, String csrf)
            throws IOException, InterruptedException {
        HttpRequest request = request(path)
                .header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.http().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(SessionClient client, String path) throws IOException, InterruptedException {
        return client.http().send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
    }

    private static SessionClient client() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new SessionClient(http, cookies);
    }

    private record SessionClient(HttpClient http, CookieManager cookies) {
        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("FOLIO_SESSION"))
                    .findFirst()
                    .map(java.net.HttpCookie::getValue)
                    .orElse(null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BrokerStubConfiguration {

        @Bean
        @Order(0)
        SecurityFilterChain brokerStubSecurity(HttpSecurity http) throws Exception {
            http.securityMatcher("/__itest/**")
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .csrf(AbstractHttpConfigurer::disable);
            return http.build();
        }
    }

    @RestController
    static class BrokerStubController {

        private final ListenerIdentityService listenerIdentityService;
        private final BrokerIdentity brokerIdentity;
        private final SecurityContextRepository securityContextRepository;
        private final Clock clock;

        BrokerStubController(
                ListenerIdentityService listenerIdentityService,
                BrokerIdentity brokerIdentity,
                SecurityContextRepository securityContextRepository,
                Clock clock) {
            this.listenerIdentityService = listenerIdentityService;
            this.brokerIdentity = brokerIdentity;
            this.securityContextRepository = securityContextRepository;
            this.clock = clock;
        }

        @PostMapping("/__itest/broker/{provider}")
        ResponseEntity<Void> authenticate(
                @PathVariable String provider,
                @RequestHeader("X-Test-Subject") String subject,
                @RequestHeader(value = "X-Test-Email", required = false) String email,
                @RequestHeader("X-Test-Name") String displayName,
                @RequestHeader("X-Test-Amr") String authenticationMethods,
                HttpServletRequest request,
                HttpServletResponse response) {
            try {
                SignInProvider signInProvider = SignInProvider.fromRegistrationId(provider);
                Instant now = clock.instant();
                Map<String, Object> claims = new HashMap<>();
                claims.put("iss", "https://login.eu.example");
                claims.put("sub", "zitadel-" + subject);
                claims.put("aud", List.of("folio-test"));
                claims.put("auth_time", now);
                claims.put("amr", List.of(authenticationMethods.split(" ")));
                claims.put("folio_external_issuer", providerIssuer(signInProvider));
                claims.put("folio_external_subject", subject);
                claims.put("name", displayName);
                if (email != null) {
                    claims.put("email", email);
                }
                OidcIdToken idToken = new OidcIdToken("server-side-only", now.minusSeconds(1), now.plusSeconds(300), claims);
                ExternalIdentity externalIdentity = brokerIdentity.from(
                        signInProvider, new DefaultOidcUser(List.of(), idToken));

                HttpSession httpSession = request.getSession(true);
                Object pendingListener = httpSession.getAttribute(IdentityLinkCeremony.LISTENER_ID);
                Object pendingProvider = httpSession.getAttribute(IdentityLinkCeremony.PROVIDER);
                ListenerSession listener;
                if (pendingListener instanceof UUID listenerId && signInProvider.name().equals(pendingProvider)) {
                    listener = listenerIdentityService.link(listenerId, externalIdentity);
                } else if (pendingListener == null && pendingProvider == null) {
                    listener = listenerIdentityService.establish(externalIdentity);
                } else {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
                httpSession.removeAttribute(IdentityLinkCeremony.LISTENER_ID);
                httpSession.removeAttribute(IdentityLinkCeremony.PROVIDER);
                request.changeSessionId();
                httpSession.setAttribute(SessionLifecycleFilter.LAST_ROTATION, clock.millis());

                ListenerPrincipal principal = new ListenerPrincipal(
                        listener.listenerId(), listener.displayName(), listener.contactEmail(), listener.providers(), signInProvider, now);
                UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                securityContextRepository.saveContext(context, request, response);
                return ResponseEntity.noContent().build();
            } catch (BrokerAuthenticationException invalidBrokerResult) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            } catch (IdentityLinkConflictException conflict) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
        }

        private static String providerIssuer(SignInProvider provider) {
            return switch (provider) {
                case GOOGLE -> "https://accounts.google.com";
                case APPLE -> "https://appleid.apple.com";
                case FACEBOOK -> "https://www.facebook.com";
            };
        }
    }
}
