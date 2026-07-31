package dev.audiobook.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("itest")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentitySessionITest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final int BROKER_HOST_PORT = 29_121;

    @Container
    static final FixedPortBrokerContainer BROKER_CONTAINER = new FixedPortBrokerContainer()
            .withCopyFileToContainer(MountableFile.forClasspathResource("broker-server.mjs"), "/broker-server.mjs")
            .withCommand("node", "/broker-server.mjs")
            .waitingFor(Wait.forListeningPort());

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    IdentitySessionITest(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void realOAuthCallbacksCoverProviderMfaLinkingRecoveryRotationAndIsolation() throws Exception {
        SessionClient first = client();
        csrf(first);
        assertThat(first.cookies().getCookieStore().getCookies())
                .as("session cookies")
                .extracting(java.net.HttpCookie::getName)
                .contains("FOLIO_SESSION");
        String anonymousSession = first.sessionId();
        assertThat(authenticate(first, "google", scenario("google-one", "same@example.test", "First Listener", "mfa", "otp")))
                .isEqualTo("/");
        assertThat(first.sessionId()).isNotEqualTo(anonymousSession);
        assertThat(get(first, "/api/v1/auth/session").body()).contains("\"authenticated\":true");

        String authenticatedSession = first.sessionId();
        Thread.sleep(150);
        HttpResponse<String> firstLibrary = get(first, "/api/v1/library");
        assertThat(firstLibrary.statusCode()).isEqualTo(200);
        assertThat(first.sessionId()).isNotEqualTo(authenticatedSession);
        assertThat(firstLibrary.body())
                .contains("First Listener", "same@example.test", "google")
                .doesNotContain("google-one", "access_token", "id_token", "listenerId");

        SessionClient second = client();
        csrf(second);
        authenticate(second, "facebook", scenario("facebook-two", "same@example.test", "Second Listener", "mfa", "otp"));
        assertThat(get(second, "/api/v1/library").body())
                .contains("Second Listener", "facebook")
                .doesNotContain("First Listener", "google-one");

        SessionClient missingEmail = client();
        csrf(missingEmail);
        authenticate(missingEmail, "apple", scenario("apple-no-email", null, "No Email", "mfa", "otp"));
        assertThat(get(missingEmail, "/api/v1/library").body())
                .contains("No Email", "apple", "\"contactEmail\":null");

        startLink(first, "apple");
        authenticate(first, "apple", scenario(
                "shared-apple", "relay@privaterelay.appleid.com", "First Listener", "mfa", "otp"));
        assertThat(get(first, "/api/v1/library").body()).contains("apple", "google");

        startLink(first, "facebook");
        authenticate(first, "facebook", scenario("facebook-one", null, "First Listener", "mfa", "otp"));
        assertThat(get(first, "/api/v1/library").body()).contains("apple", "facebook", "google");

        startLink(missingEmail, "facebook");
        authenticate(missingEmail, "facebook", scenario("facebook-three", null, "No Email", "mfa", "otp"));
        assertThat(get(missingEmail, "/api/v1/library").body()).contains("apple", "facebook");

        SessionClient returning = client();
        csrf(returning);
        authenticate(returning, "google", scenario("google-one", null, "Ignored Metadata", "mfa", "otp"));
        assertThat(get(returning, "/api/v1/library").body())
                .contains("First Listener", "apple", "facebook", "google")
                .doesNotContain("Ignored Metadata");

        startLink(second, "apple");
        String denied = authenticate(second, "apple", scenario("shared-apple", null, "Second Listener", "mfa", "otp"));
        assertThat(denied).isEqualTo("/?sign-in=failed");
        assertThat(get(second, "/api/v1/library").statusCode()).isEqualTo(401);

        SessionClient mismatchedLink = client();
        csrf(mismatchedLink);
        authenticate(mismatchedLink, "google", scenario("mismatch-current", null, "Mismatch", "mfa", "otp"));
        startLink(mismatchedLink, "apple");
        assertThat(authenticate(mismatchedLink, "facebook", scenario(
                "mismatch-target", null, "Mismatch", "mfa", "otp")))
                .isEqualTo("/?sign-in=failed");
        assertThat(get(mismatchedLink, "/api/v1/library").statusCode()).isEqualTo(401);

        for (String provider : List.of("google", "apple", "facebook")) {
            SessionClient rejected = client();
            csrf(rejected);
            String failure = authenticate(rejected, provider, scenario("no-mfa-" + provider, null, "Rejected", "pwd"));
            assertThat(failure).isEqualTo("/?sign-in=failed");
            assertThat(get(rejected, "/api/v1/library").statusCode()).isEqualTo(401);
        }

        SessionClient unavailableBroker = client();
        csrf(unavailableBroker);
        assertThat(authenticate(unavailableBroker, "google", scenario(
                "token-failure", null, "Unavailable", "mfa", "otp")))
                .isEqualTo("/?sign-in=failed");
        assertThat(get(unavailableBroker, "/api/v1/library").statusCode()).isEqualTo(401);

        String logoutCsrf = csrf(first);
        assertThat(apiPost(first, "/api/v1/auth/logout", logoutCsrf).statusCode()).isEqualTo(204);
        assertThat(get(first, "/api/v1/library").statusCode()).isEqualTo(401);

        SessionClient recoveryClient = missingEmail;
        String recoveryCsrf = csrf(recoveryClient);
        HttpResponse<String> recovery = apiPost(recoveryClient, "/api/v1/auth/recovery", recoveryCsrf);
        assertThat(recovery.statusCode()).isEqualTo(303);
        assertThat(recovery.headers().firstValue("location")).contains("https://login.eu.example/ui/v2/login");
        assertThat(get(recoveryClient, "/api/v1/library").statusCode()).isEqualTo(401);

        SessionClient staleLink = client();
        csrf(staleLink);
        authenticate(staleLink, "google", scenario("stale-current", null, "Stale Link", "mfa", "otp"));
        Thread.sleep(5_200);
        String staleCsrf = csrf(staleLink);
        HttpResponse<String> currentReauthentication = htmlPost(
                staleLink, "/api/v1/auth/links/facebook", staleCsrf);
        assertThat(currentReauthentication.statusCode()).isEqualTo(303);
        assertThat(currentReauthentication.headers().firstValue("location"))
                .contains("/oauth2/authorization/google");
        assertThat(authenticate(staleLink, "google", scenario(
                "stale-current", null, "Stale Link", "mfa", "otp")))
                .startsWith("/oauth2/authorization/facebook");
        authenticate(staleLink, "facebook", scenario("stale-target", null, "Stale Link", "mfa", "otp"));
        assertThat(get(staleLink, "/api/v1/library").body()).contains("facebook", "google");

        SessionClient idle = client();
        csrf(idle);
        authenticate(idle, "google", scenario("idle-listener", null, "Idle Listener", "mfa", "otp"));
        Thread.sleep(8_200);
        assertThat(get(idle, "/api/v1/library").statusCode()).isEqualTo(401);
    }

    @Test
    void concurrentFirstCallbacksEstablishOneListenerIdentity() throws Exception {
        jdbcTemplate.execute("""
                CREATE FUNCTION delay_concurrent_identity_link() RETURNS trigger AS $$
                BEGIN
                    IF NEW.subject = 'google-concurrent-race' THEN
                        PERFORM pg_sleep(0.5);
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER delay_concurrent_identity_link
                BEFORE INSERT ON external_identity_link
                FOR EACH ROW EXECUTE FUNCTION delay_concurrent_identity_link()
                """);

        SessionClient first = client();
        SessionClient second = client();
        csrf(first);
        csrf(second);
        BrokerScenario scenario = scenario(
                "google-concurrent-race", null, "Concurrent Listener", "mfa", "otp");
        PreparedAuthentication firstCallback = prepareAuthentication(first, "google", scenario);
        PreparedAuthentication secondCallback = prepareAuthentication(second, "google", scenario);
        CountDownLatch start = new CountDownLatch(1);

        try (var callbacks = Executors.newFixedThreadPool(2)) {
            Future<String> firstResult = callbacks.submit(() -> completeAuthentication(firstCallback, start));
            Future<String> secondResult = callbacks.submit(() -> completeAuthentication(secondCallback, start));
            start.countDown();

            assertThat(firstResult.get(10, TimeUnit.SECONDS)).isEqualTo("/");
            assertThat(secondResult.get(10, TimeUnit.SECONDS)).isEqualTo("/");
        }

        assertThat(get(first, "/api/v1/library").body()).contains("Concurrent Listener", "google");
        assertThat(get(second, "/api/v1/library").body()).contains("Concurrent Listener", "google");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM external_identity_link WHERE issuer = ? AND subject = ?",
                Integer.class,
                "https://accounts.google.com",
                "google-concurrent-race"))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listener_identity WHERE display_name = ?",
                Integer.class,
                "Concurrent Listener"))
                .isEqualTo(1);
    }

    private void startLink(SessionClient client, String provider) throws Exception {
        HttpResponse<String> response = apiPost(client, "/api/v1/auth/links/" + provider, csrf(client));
        assertThat(response.statusCode()).isEqualTo(202);
    }

    private String authenticate(SessionClient client, String provider, BrokerScenario scenario) throws Exception {
        return completeAuthentication(prepareAuthentication(client, provider, scenario), null);
    }

    private PreparedAuthentication prepareAuthentication(
            SessionClient client,
            String provider,
            BrokerScenario scenario) throws Exception {
        HttpResponse<String> authorization = get(client, "/oauth2/authorization/" + provider);
        assertThat(authorization.statusCode()).isBetween(300, 399);
        URI location = URI.create(authorization.headers().firstValue("location").orElseThrow());
        Map<String, String> parameters = queryParameters(location);
        assertThat(parameters)
                .containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("prompt", "login")
                .containsEntry("max_age", "0")
                .containsEntry("idp", provider + "-idp");
        assertThat(parameters.get("code_challenge")).isNotBlank();

        BrokerScenario authorizedScenario = scenario.withNonce(parameters.get("nonce"));
        String scenarioCode = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(authorizedScenario));
        String callback = "/login/oauth2/code/" + provider
                + "?code=" + encode("code-" + scenarioCode)
                + "&state=" + encode(parameters.get("state"));
        return new PreparedAuthentication(client, callback, scenario);
    }

    private String completeAuthentication(
            PreparedAuthentication prepared,
            CountDownLatch start) throws Exception {
        if (start != null) {
            start.await(10, TimeUnit.SECONDS);
        }
        HttpResponse<String> response = get(prepared.client(), prepared.callback());
        assertThat(response.statusCode()).isBetween(300, 399);
        assertThat(response.body()).doesNotContain(
                prepared.scenario().subject(), "same@example.test", "relay@privaterelay.appleid.com");
        URI redirect = URI.create(response.headers().firstValue("location").orElseThrow());
        return redirect.getRawPath() + (redirect.getRawQuery() == null ? "" : "?" + redirect.getRawQuery());
    }

    private String csrf(SessionClient client) throws Exception {
        HttpResponse<String> response = get(client, "/api/v1/auth/session");
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("csrf").path("token").asText();
    }

    private HttpResponse<String> apiPost(SessionClient client, String path, String csrf) throws Exception {
        HttpRequest request = request(path)
                .header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.http().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> htmlPost(SessionClient client, String path, String csrf) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/html")
                .header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.http().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(SessionClient client, String path) throws Exception {
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

    private static BrokerScenario scenario(String subject, String email, String displayName, String... methods) {
        return new BrokerScenario(subject, email, displayName, List.of(methods), null);
    }

    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
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

    private record PreparedAuthentication(
            SessionClient client,
            String callback,
            BrokerScenario scenario) {
    }

    private record BrokerScenario(
            String subject,
            String email,
            String displayName,
            List<String> authenticationMethods,
            String nonce) {

        BrokerScenario withNonce(String value) {
            return new BrokerScenario(subject, email, displayName, authenticationMethods, value);
        }
    }

    private static final class FixedPortBrokerContainer extends GenericContainer<FixedPortBrokerContainer> {

        private FixedPortBrokerContainer() {
            super(DockerImageName.parse("node:22-alpine"));
            addFixedExposedPort(BROKER_HOST_PORT, 8080);
        }
    }

}
