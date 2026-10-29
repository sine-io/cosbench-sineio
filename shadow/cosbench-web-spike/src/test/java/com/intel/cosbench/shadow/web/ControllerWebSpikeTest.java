package com.intel.cosbench.shadow.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.intel.cosbench.model.WorkloadState;
import com.intel.cosbench.service.ControllerService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ControllerWebSpikeTest {

    private static final String BASE = "http://127.0.0.1:19088/controller";
    private static final byte[] WORKLOAD = readWorkloadFixture();
    private static final Path TEST_HOME = createTestHome();

    @Autowired
    private ControllerService controllerService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("cosbench.config", () -> TEST_HOME.resolve("conf/controller.yml").toString());
        properties.add("cosbench.web.username", () -> "anonymous");
        properties.add("cosbench.web.password", () -> "cosbench");
    }

    @Test
    void legacyPageAndStaticResourcesRunBehindJakartaSessionSecurity() throws Exception {
        assertThat(controllerService.getClass().getName())
                .isEqualTo("com.intel.cosbench.controller.service.COSBControllerService");

        HttpClient anonymous = client();
        HttpResponse<String> protectedPage = get(anonymous, "/index.html");
        assertThat(protectedPage.statusCode()).isEqualTo(302);
        assertThat(protectedPage.headers().firstValue("Location").orElse(""))
                .endsWith("/controller/logon.html");

        HttpResponse<String> loginPage = get(anonymous, "/logon.html");
        assertThat(loginPage.statusCode())
                .as("logon page redirect: %s", loginPage.headers().firstValue("Location").orElse(""))
                .isEqualTo(200);
        assertThat(loginPage.body()).contains("j_username", "j_password", "j_security_check");

        HttpResponse<String> css = get(anonymous, "/resources/cosbench.css");
        assertThat(css.statusCode()).isEqualTo(200);
        assertThat(css.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/css");
        assertThat(css.body()).contains("#header", ".content");
        HttpResponse<byte[]> javascript = getBytes(anonymous, "/resources/build/dist/echarts.js");
        assertThat(javascript.statusCode()).isEqualTo(200);
        assertThat(javascript.body()).isNotEmpty();
        assertThat(javascript.headers().firstValue("Content-Type").orElse("")).contains("javascript");
        HttpResponse<byte[]> image = getBytes(anonymous, "/resources/bg-header.png");
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("Content-Type").orElse(""))
                .startsWith("image/png");

        HttpClient authenticated = client();
        HttpResponse<String> acceptedLogin = postForm(authenticated, "/j_security_check",
                "j_username=anonymous&j_password=cosbench");
        assertThat(acceptedLogin.statusCode()).isEqualTo(302);
        assertThat(acceptedLogin.headers().firstValue("Location").orElse(""))
                .endsWith("/controller/index.html");
        assertThat(get(authenticated, "/index.html").body())
                .contains("Controller Overview", "Submit new workloads", "Load archived workloads");

        HttpClient rejected = client();
        HttpResponse<String> failedLogin = postForm(rejected, "/j_security_check",
                "j_username=anonymous&j_password=wrong");
        assertThat(failedLogin.statusCode()).isEqualTo(302);
        assertThat(failedLogin.headers().firstValue("Location").orElse(""))
                .endsWith("/controller/logonError.html");
        assertThat(get(rejected, "/logonError.html").body())
                .contains("no access permission");
    }

    @Test
    void multipartCliSubmissionAndDownloadsUseTheRealControllerService() throws Exception {
        HttpClient authenticated = client();
        postForm(authenticated, "/j_security_check", "j_username=anonymous&j_password=cosbench");

        HttpResponse<String> submitted = postMultipart(client(),
                "/cli/submit.action?username=anonymous&password=cosbench", "config", "web-spike.xml", WORKLOAD);
        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(submitted.body()).matches("Accepted with ID: w[0-9]+");
        String id = submitted.body().substring("Accepted with ID: ".length());
        awaitArchived(id);

        HttpResponse<byte[]> config = getBytes(authenticated, "/download-config.do?id=" + id);
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(config.headers().firstValue("Content-Disposition").orElse(""))
                .contains("workload-config.xml");
        assertThat(config.body()).as("downloaded config content length header %s",
                config.headers().firstValue("Content-Length").orElse("missing")).isNotEmpty();
        assertThat(new String(config.body(), StandardCharsets.UTF_8)).contains("baseline-noop");

        HttpResponse<byte[]> log = getBytes(authenticated, "/download-log.do?id=" + id);
        assertThat(log.statusCode()).isEqualTo(200);
        assertThat(log.headers().firstValue("Content-Disposition").orElse(""))
                .contains("workload-log.txt");
        assertThat(log.body()).isNotEmpty();

        assertThat(getBytes(client(), "/download-log.do?id=" + id).statusCode()).isEqualTo(302);
        assertThat(getBytes(authenticated, "/download-log.do?id=missing").statusCode()).isEqualTo(404);
    }

    @Test
    void cliFilterReportsAuthenticationAndRequestErrorsAndEnforcesUploadLimit() throws Exception {
        HttpResponse<String> missingCredentials = postMultipart(client(), "/cli/submit.action",
                "config", "web-spike.xml", WORKLOAD);
        assertThat(missingCredentials.statusCode()).isEqualTo(200);
        assertThat(missingCredentials.body())
                .isEqualTo("Please input complete user info [username:password] to get authentication!");

        HttpResponse<String> badCredentials = postMultipart(client(),
                "/cli/submit.action?username=anonymous&password=wrong", "config", "web-spike.xml", WORKLOAD);
        assertThat(badCredentials.statusCode()).isEqualTo(200);
        assertThat(badCredentials.body()).isEqualTo("User info [username:password] incorrect !");

        HttpResponse<String> missingConfig = postMultipart(client(),
                "/cli/submit.action?username=anonymous&password=cosbench", null, null, new byte[0]);
        assertThat(missingConfig.statusCode()).isEqualTo(200);
        assertThat(missingConfig.body()).isEqualTo("Bad Request");

        int uploadLimit = 10 * 1024 * 1024;
        byte[] atLimit = new byte[uploadLimit];
        Arrays.fill(atLimit, (byte) ' ');
        System.arraycopy(WORKLOAD, 0, atLimit, 0, WORKLOAD.length);
        HttpResponse<String> acceptedAtLimit = postMultipart(client(),
                "/cli/submit.action?username=anonymous&password=cosbench", "config", "limit.xml", atLimit);
        assertThat(acceptedAtLimit.statusCode()).isEqualTo(200);
        assertThat(acceptedAtLimit.body()).matches("Accepted with ID: w[0-9]+");

        byte[] oversized = new byte[uploadLimit + 1];
        HttpResponse<String> rejectedUpload = postMultipart(client(),
                "/cli/submit.action?username=anonymous&password=cosbench", "config", "large.xml", oversized);
        assertThat(rejectedUpload.statusCode()).isEqualTo(413);
    }

    private void awaitArchived(String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var workload = controllerService.getWorkloadInfo(id);
            if (workload != null && workload.getState() == WorkloadState.TERMINATED
                    && controllerService.getWorkloadConfig(workload).isFile()
                    && controllerService.getWorkloadConfig(workload).length() > 0
                    && controllerService.getWorkloadLog(workload).isFile()) {
                return;
            }
            Thread.sleep(25);
        }
        var workload = controllerService.getWorkloadInfo(id);
        assertThat(workload.getState()).isEqualTo(WorkloadState.TERMINATED);
        var configFile = controllerService.getWorkloadConfig(workload);
        assertThat(configFile.exists()).as("archived workload config: %s", configFile).isTrue();
        assertThat(configFile.isFile()).isTrue();
        assertThat(configFile.length()).isGreaterThan(0L);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<byte[]> getBytes(HttpClient client, String path)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<String> postForm(HttpClient client, String path, String body)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> postMultipart(HttpClient client, String path, String field,
            String filename, byte[] content) throws IOException, InterruptedException {
        String boundary = "cosbench-" + UUID.randomUUID();
        String partName = field == null ? "other" : field;
        String partFilename = filename == null ? "ignored.txt" : filename;
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + partName
                + "\"; filename=\"" + partFilename + "\"\r\nContent-Type: application/xml\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[header.length + content.length + footer.length];
        System.arraycopy(header, 0, body, 0, header.length);
        System.arraycopy(content, 0, body, header.length, content.length);
        System.arraycopy(footer, 0, body, header.length + content.length, footer.length);
        return client.send(HttpRequest.newBuilder(URI.create(BASE + path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] readWorkloadFixture() {
        try (var input = ControllerWebSpikeTest.class.getResourceAsStream("/baseline/mock-noop.xml")) {
            if (input == null) {
                throw new IllegalStateException("missing workload fixture");
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Path createTestHome() {
        try {
            Path home = Files.createTempDirectory("cosbench-web-spike-");
            Path conf = Files.createDirectories(home.resolve("conf"));
            try (var input = ControllerWebSpikeTest.class.getResourceAsStream("/web-spike-controller.yml")) {
                if (input == null) {
                    throw new IllegalStateException("missing controller config fixture");
                }
                Files.copy(input, conf.resolve("controller.yml"));
            }
            return home;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
