package dev.audiobook.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("itest")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentitySessionITest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final BrokerServer BROKER = BrokerServer.start();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("audiobook")
            .withUsername("audiobook")
            .withPassword("integration-test-only");

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.client.provider.zitadel.authorization-uri", BROKER::authorizationUri);
        registry.add("spring.security.oauth2.client.provider.zitadel.token-uri", BROKER::tokenUri);
        registry.add("spring.security.oauth2.client.provider.zitadel.user-info-uri", BROKER::userInfoUri);
        registry.add("spring.security.oauth2.client.provider.zitadel.jwk-set-uri", BROKER::jwkSetUri);
        registry.add("platform.identity.broker-issuer", BROKER::issuer);
        registry.add("platform.identity.session-rotation-interval", () -> "100ms");
        registry.add("spring.session.timeout", () -> "5s");
    }

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterAll
    static void stopBroker() {
        BROKER.close();
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

        startLink(second, "apple");
        String denied = authenticate(second, "apple", scenario("shared-apple", null, "Second Listener", "mfa", "otp"));
        assertThat(denied).isEqualTo("/?sign-in=failed");
        assertThat(get(second, "/api/v1/library").statusCode()).isEqualTo(401);

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

        SessionClient idle = client();
        csrf(idle);
        authenticate(idle, "google", scenario("idle-listener", null, "Idle Listener", "mfa", "otp"));
        Thread.sleep(5_200);
        assertThat(get(idle, "/api/v1/library").statusCode()).isEqualTo(401);
    }

    private void startLink(SessionClient client, String provider) throws Exception {
        HttpResponse<String> response = apiPost(client, "/api/v1/auth/links/" + provider, csrf(client));
        assertThat(response.statusCode()).isEqualTo(202);
    }

    private String authenticate(SessionClient client, String provider, BrokerScenario scenario) throws Exception {
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

        BROKER.answer(scenario.withNonce(parameters.get("nonce")));
        String callback = "/login/oauth2/code/" + provider
                + "?code=" + encode("code-" + scenario.subject())
                + "&state=" + encode(parameters.get("state"));
        HttpResponse<String> response = get(client, callback);
        assertThat(response.statusCode()).isBetween(300, 399);
        assertThat(response.body()).doesNotContain(
                scenario.subject(), "same@example.test", "relay@privaterelay.appleid.com");
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

    private static final class BrokerServer implements AutoCloseable {

        private static final ObjectMapper JSON = new ObjectMapper();
        private final HttpServer server;
        private final RSAKey signingKey;
        private final AtomicReference<BrokerScenario> scenario = new AtomicReference<>();

        private BrokerServer(HttpServer server, RSAKey signingKey) {
            this.server = server;
            this.signingKey = signingKey;
        }

        static BrokerServer start() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyID("folio-itest")
                        .build();
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                BrokerServer broker = new BrokerServer(server, key);
                server.createContext("/oauth/v2/token", broker::token);
                server.createContext("/oidc/v1/userinfo", broker::userInfo);
                server.createContext("/oauth/v2/keys", broker::keys);
                server.start();
                return broker;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String authorizationUri() {
            return issuer() + "/oauth/v2/authorize";
        }

        String tokenUri() {
            return issuer() + "/oauth/v2/token";
        }

        String userInfoUri() {
            return issuer() + "/oidc/v1/userinfo";
        }

        String jwkSetUri() {
            return issuer() + "/oauth/v2/keys";
        }

        void answer(BrokerScenario answer) {
            scenario.set(answer);
        }

        private void token(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!request.contains("grant_type=authorization_code") || !request.contains("code_verifier=")) {
                respond(exchange, 400, "{\"error\":\"invalid_request\"}");
                return;
            }
            BrokerScenario answer = scenario.get();
            if (answer.subject().equals("token-failure")) {
                respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
                return;
            }
            try {
                String token = idToken(answer);
                respond(exchange, 200, "{\"access_token\":\"server-only-access\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":300,\"id_token\":\"" + token + "\"}");
            } catch (Exception failure) {
                respond(exchange, 500, "{\"error\":\"server_error\"}");
            }
        }

        private void userInfo(HttpExchange exchange) throws IOException {
            BrokerScenario answer = scenario.get();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", "zitadel-" + answer.subject());
            claims.put("name", answer.displayName());
            if (answer.email() != null) {
                claims.put("email", answer.email());
            }
            respond(exchange, 200, JSON.writeValueAsString(claims));
        }

        private void keys(HttpExchange exchange) throws IOException {
            respond(exchange, 200, "{\"keys\":[" + signingKey.toPublicJWK().toJSONString() + "]}");
        }

        private String idToken(BrokerScenario answer) throws Exception {
            Instant now = Instant.now();
            String provider = answer.subject().split("-", 2)[0];
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer())
                    .subject("zitadel-" + answer.subject())
                    .audience("folio-test")
                    .issueTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("auth_time", Date.from(now))
                    .claim("nonce", answer.nonce())
                    .claim("amr", answer.authenticationMethods())
                    .claim("folio_external_issuer", providerIssuer(provider))
                    .claim("folio_external_subject", answer.subject())
                    .claim("name", answer.displayName());
            if (answer.email() != null) {
                claims.claim("email", answer.email());
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(signingKey.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        }

        private static String providerIssuer(String provider) {
            return switch (provider) {
                case "google" -> "https://accounts.google.com";
                case "apple", "shared" -> "https://appleid.apple.com";
                case "facebook" -> "https://www.facebook.com";
                default -> "https://identity.example";
            };
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
