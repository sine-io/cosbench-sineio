package com.intel.cosbench.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = DriverHttpContractTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DriverHttpContractTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AtomicInteger calls;

    @Test
    void serviceTokenAndIdempotencyContractRunsOverRealHttp() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/i/submit.command");

        HttpResponse<String> cookieOnly = post(client, uri, "mission", true, false);
        assertThat(cookieOnly.statusCode()).isEqualTo(401);
        assertThat(cookieOnly.body()).contains("service authentication failed");

        HttpResponse<String> missingIdentity = post(client, uri, "mission", false, false);
        assertThat(missingIdentity.statusCode()).isEqualTo(400);
        assertThat(missingIdentity.body()).contains("request identity is required");

        HttpResponse<String> accepted = post(client, uri, "mission", false, true);
        HttpResponse<String> replay = post(client, uri, "mission", false, true);
        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(accepted.body()).isEqualTo("{\"id\":\"m-1\"}");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(accepted.body());
        assertThat(calls).hasValue(1);

        HttpResponse<String> conflict = post(client, uri, "changed", false, true);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(calls).hasValue(1);
    }

    @Test
    void differingRequestLineageAndIdempotencyKeyIsRejected() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://127.0.0.1:" + port + "/i/submit.command");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer contract-token")
                .header("Idempotency-Key", "key-a")
                .header("X-COSBench-Request-Id", "request-b")
                .POST(HttpRequest.BodyPublishers.ofString("mission"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("headers must match");
        assertThat(calls).hasValue(0);
    }

    private HttpResponse<String> post(HttpClient client, URI uri, String body,
            boolean cookieOnly, boolean identity) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookieOnly) {
            request.header("Cookie", "JSESSIONID=page-session");
        } else {
            request.header("Authorization", "Bearer contract-token");
        }
        if (identity) {
            request.header("Idempotency-Key", "contract-request-1")
                    .header("X-COSBench-Request-Id", "contract-request-1");
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @TestConfiguration
    @Import(DriverCommandController.class)
    static class Config {
        @Bean
        ServiceAuth serviceAuth() {
            return new ServiceAuth("contract-token");
        }

        @Bean
        IdempotencyStore idempotencyStore() {
            return new IdempotencyStore();
        }

        @Bean
        AtomicInteger calls() {
            return new AtomicInteger();
        }

        @Bean
        DriverCommandController.DriverCommandPort driverCommandPort(AtomicInteger calls) {
            return new DriverCommandController.DriverCommandPort() {
                @Override public DriverCommandResult submit(byte[] body) {
                    calls.incrementAndGet();
                    return new DriverCommandResult(201, "{\"id\":\"m-1\"}");
                }
                @Override public DriverCommandResult ping(byte[] body) { return ok(); }
                @Override public DriverCommandResult login(byte[] body) { return ok(); }
                @Override public DriverCommandResult launch(byte[] body) { return ok(); }
                @Override public DriverCommandResult query(byte[] body) { return ok(); }
                @Override public DriverCommandResult close(byte[] body) { return ok(); }
                @Override public DriverCommandResult abort(byte[] body) { return ok(); }
                @Override public DriverCommandResult trigger(byte[] body) { return ok(); }
                private DriverCommandResult ok() { return new DriverCommandResult(200, "{}"); }
            };
        }
    }
}
