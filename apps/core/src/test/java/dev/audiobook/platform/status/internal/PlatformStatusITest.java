package dev.audiobook.platform.status.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import dev.audiobook.platform.PlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformStatusITest {

    @LocalServerPort
    private int port;

    @Test
    void migratedPostgresIsObservableThroughThePublicApi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/platform/status"))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.body())
                .contains("\"version\":\"itest\"")
                .contains("\"revision\":\"test-revision\"")
                .contains("\"core\":\"AVAILABLE\"")
                .contains("\"database\":\"AVAILABLE\"")
                .doesNotContain("jdbc:")
                .doesNotContain("audiobook");
    }
}
