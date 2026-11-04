package com.intel.cosbench.testing.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import java.net.CookieManager;
import java.net.CookiePolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.io.TempDir;

class ControllerProcessTest {
    @TempDir Path home;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    @Test
    void controllerPagesAndCliKeepTheLegacyOperationContract() throws Exception {
        try (var app = start()) {
            HttpClient browser = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(1)).build();

            var protectedIndex = request(browser, app, "/index.html");
            assertEquals(302, protectedIndex.statusCode());
            HttpResponse<String> logon;
            try {
                logon = request(browser, app, "/logon.html");
            } catch (java.io.IOException failure) {
                fail("GET /logon.html failed: " + failure + "\n" + readLog(app)
                        + "\n" + readSystemLog());
                return;
            }
            assertEquals(200, logon.statusCode(), logon.body());
            assertTrue(logon.body().contains("j_username"), logon.body());
            assertEquals(200, request(browser, app, "/resources/cosbench.css").statusCode());
            assertEquals(200, request(browser, app, "/resources/build/dist/echarts.js").statusCode());
            assertEquals(200, request(browser, app, "/favicon.ico").statusCode());
            var login = postForm(browser, app, "/j_security_check",
                    "j_username=anonymous&j_password=cosbench");
            assertEquals(302, login.statusCode());

            var index = request(browser, app, "/index.html");
            assertEquals(200, index.statusCode(), index.body());
            assertTrue(index.body().contains("Controller Overview"));
            assertTrue(index.body().contains("name=\"cancelIds\""));
            assertTrue(index.body().contains("name=\"resubmitIds\""));
            assertTrue(request(browser, app, "/matrix.html?type=histo").body().contains("Performance Matrix"));
            assertTrue(request(browser, app, "/submit.html").body().contains("action=\"submit-workload.do\""));
            assertTrue(request(browser, app, "/config.html").body().contains("action=\"config-workload.do\""));
            assertTrue(request(browser, app, "/advanced-config.html").body()
                    .contains("action=\"advanced-config-workload.do\""));
            var generatedConfig = postForm(browser, app, "/config-workload.do",
                    "workload.name=page-config&auth.type=none&storage.type=none&init.checked=on"
                            + "&init.workers=1&init.containers=r&init.containers.min=1&init.containers.max=1");
            assertEquals(200, generatedConfig.statusCode(), generatedConfig.body());
            assertTrue(generatedConfig.headers().firstValue("Content-Type").orElse("").startsWith("application/xml"),
                    generatedConfig.body());
            assertTrue(generatedConfig.body().contains("<workload name=\"page-config\""), generatedConfig.body());
            var generatedMatrix = postForm(browser, app, "/advanced-config-workload.do",
                    "workload.matrix.name=web-advanced&auth.type=none&storage.type=mock&storage.config=delay%3D0"
                            + "&runtime=1&rampup=0&delay=0&num_of_drivers=1&workload.name=advanced-mock"
                            + "&workload-number=0&object-sizes=1&object-size-unit=B&num-of-objects=1"
                            + "&num-of-containers=1&workers=1&read-ratio0=100&write-ratio0=0&delete-ratio0=0"
                            + "&generate-workload=Generate");
            assertEquals(200, generatedMatrix.statusCode(), generatedMatrix.body());
            assertTrue(generatedMatrix.body().contains("Workload Submission"), generatedMatrix.body());
            assertTrue(Files.readString(home.resolve("launch/workloads/web-advanced/advanced-mock.xml"))
                    .contains("<workload name=\"advanced-mock\""));

            byte[] workload = actionWorkload().getBytes(StandardCharsets.UTF_8);
            var submitted = postMultipart(browser, app, "/submit-workload.do", workload);
            assertEquals(200, submitted.statusCode(), submitted.body());
            assertTrue(submitted.body().contains("your workload has been accepted"), submitted.body());
            assertTrue(submitted.body().contains("w1"), submitted.body());
            awaitArchived(app, "w1");
            var workloadPage = request(browser, app, "/workload.html?id=w1");
            assertEquals(200, workloadPage.statusCode(), workloadPage.body());
            assertTrue(workloadPage.body().contains("web-actions"), workloadPage.body());
            assertEquals(200, request(browser, app, "/cancel-workload.do?id=w1").statusCode());

            var cliIndex = request(http, app, "/cli/index.action?username=anonymous&password=cosbench");
            assertEquals(200, cliIndex.statusCode(), cliIndex.body());
            assertTrue(cliIndex.body().contains("Drivers:"), cliIndex.body());
            var cliSubmit = postMultipart(http, app,
                    "/cli/submit.action?username=anonymous&password=cosbench", workload);
            assertEquals(200, cliSubmit.statusCode(), cliSubmit.body());
            assertTrue(cliSubmit.body().matches("(?s)Accepted with ID: w[0-9]+"), cliSubmit.body());
            var cliConfig = postMultipart(http, app,
                    "/cli/config.action?username=anonymous&password=cosbench", workload);
            assertEquals(200, cliConfig.statusCode(), cliConfig.body());
            assertTrue(cliConfig.body().matches("(?s)Accepted with ID: w[0-9]+"), cliConfig.body());
            var cliWorkload = request(http, app,
                    "/cli/workload.action?username=anonymous&password=cosbench&id=w2");
            assertEquals(200, cliWorkload.statusCode(), cliWorkload.body());
            assertTrue(cliWorkload.body().startsWith("w2\t"), cliWorkload.body());
            var cliCancel = request(http, app,
                    "/cli/cancel.action?username=anonymous&password=cosbench&id=w2");
            assertEquals(200, cliCancel.statusCode(), cliCancel.body());
            assertTrue(cliCancel.body().startsWith("w2\t"), cliCancel.body());
            var cliBadCredentials = request(http, app, "/cli/index.action?username=anonymous&password=wrong");
            assertEquals("User info [username:password] incorrect !", cliBadCredentials.body());
            var cliMissingCredentials = request(http, app, "/cli/index.action");
            assertEquals("Please input complete user info [username:password] to get authentication!",
                    cliMissingCredentials.body());
            assertNotEquals(200, request(http, app,
                    "/cli/resubmit.action?username=anonymous&password=cosbench&id=w2").statusCode());
            var missingConfig = postMultipart(http, app,
                    "/cli/submit.action?username=anonymous&password=cosbench", new byte[0]);
            assertEquals("Bad Request", missingConfig.body());
            var invalidXml = postMultipart(http, app,
                    "/cli/submit.action?username=anonymous&password=cosbench", "<workload".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, invalidXml.statusCode());
            assertFalse(invalidXml.body().contains("Accepted with ID:"), invalidXml.body());
        }
    }

    @Test
    void controllerObservabilityRoutesRetainTheLegacyHttpContract() throws Exception {
        try (var app = startWithWebConfiguration("/proxy", 1024, "5s")) {
            var deniedDownload = request(http, app, "/download-log.do?id=w1");
            assertEquals(302, deniedDownload.statusCode(), deniedDownload.body());
            assertTrue(deniedDownload.headers().firstValue("Location").orElse("").contains("/proxy/logon.html"));

            var root = request(http, app, "/");
            assertEquals(302, root.statusCode(), root.body());
            assertTrue(root.headers().firstValue("Location").orElse("").contains("/proxy/index.html"));

            HttpClient browser = HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(1)).build();
            var logon = request(browser, app, "/logon.html");
            assertEquals(200, logon.statusCode(), logon.body());
            assertTrue(logon.body().contains("href=\"resources/cosbench.css\""), logon.body());
            var logonError = request(browser, app, "/logonError.html");
            assertEquals(200, logonError.statusCode(), logonError.body());
            assertTrue(logonError.body().contains("no access permission"), logonError.body());
            assertStaticResource(browser, app, "/resources/cosbench.css", "text/css");
            assertStaticResource(browser, app, "/resources/build/dist/echarts.js", "javascript");
            assertStaticResource(browser, app, "/resources/up_arrow.png", "image/png");
            assertEquals(302, postForm(browser, app, "/j_security_check",
                    "j_username=anonymous&j_password=cosbench").statusCode());

            var malformedXml = postMultipart(browser, app, "/submit-workload.do",
                    "<workload".getBytes(StandardCharsets.UTF_8));
            assertEquals(200, malformedXml.statusCode(), malformedXml.body());
            assertTrue(malformedXml.body().contains("Workload Submission"), malformedXml.body());
            assertTrue(malformedXml.body().contains("class=\"error\""), malformedXml.body());
            assertFalse(malformedXml.body().contains("your workload has been accepted"), malformedXml.body());
            assertFalse(Files.exists(home.resolve("archive/w1-web-actions/workload-config.xml")));
            assertNoMultipartTemporaryFiles();

            var oversizedUpload = postMultipart(browser, app, "/submit-workload.do", new byte[1025]);
            assertEquals(413, oversizedUpload.statusCode(), oversizedUpload.body());
            assertFalse(Files.exists(home.resolve("archive/w1-web-actions/workload-config.xml")));
            assertNoMultipartTemporaryFiles();

            var submitted = postMultipart(browser, app, "/submit-workload.do",
                    actionWorkload().getBytes(StandardCharsets.UTF_8));
            assertEquals(200, submitted.statusCode(), submitted.body());
            assertTrue(submitted.body().contains("your workload has been accepted"), submitted.body());
            String workloadId = submitted.body().replaceFirst(
                    "(?s).*workload\\.html\\?id=(w[0-9]+).*", "$1");
            assertTrue(workloadId.matches("w[0-9]+"), submitted.body());
            awaitArchived(app, workloadId);

            var stage = request(browser, app, "/stage.html");
            assertEquals(200, stage.statusCode(), stage.body());
            assertTrue(stage.body().contains("400 Bad Request"), stage.body());

            var timeline = request(browser, app, "/timeline.html");
            assertEquals(200, timeline.statusCode(), timeline.body());
            assertTrue(timeline.body().contains("400 Bad Request"), timeline.body());

            var csv = request(browser, app, "/timeline.csv");
            assertEquals(200, csv.statusCode(), csv.body());
            assertTrue(csv.body().contains("400 Bad Request"), csv.body());

            var log = request(browser, app, "/download-log.do?id=" + workloadId);
            assertEquals(200, log.statusCode(), log.body());
            assertTrue(log.headers().firstValue("Content-Type").isEmpty(), log.headers().toString());
            assertTrue(log.headers().firstValue("Content-Disposition").orElse("")
                    .contains("workload-log.txt"));
            assertEquals(Integer.toString(log.body().getBytes(StandardCharsets.UTF_8).length),
                    log.headers().firstValue("Content-Length").orElse(""));

            var config = request(browser, app, "/download-config.do?id=" + workloadId);
            assertEquals(200, config.statusCode(), config.body());
            assertTrue(config.headers().firstValue("Content-Type").isEmpty(), config.headers().toString());
            assertTrue(config.headers().firstValue("Content-Disposition").orElse("")
                    .contains("workload-config.xml"));
            assertEquals(Integer.toString(config.body().getBytes(StandardCharsets.UTF_8).length),
                    config.headers().firstValue("Content-Length").orElse(""));
            assertTrue(config.body().contains("web-actions"), config.body());

            var missingStage = request(browser, app, "/stage.html?wid=" + workloadId + "&sid=missing");
            assertEquals(200, missingStage.statusCode(), missingStage.body());
            assertTrue(missingStage.body().contains("404 Not Found"), missingStage.body());
            assertTrue(missingStage.body().contains("href=\"index.html\""), missingStage.body());
            var missingTimeline = request(browser, app, "/timeline.html?wid=" + workloadId + "&sid=missing");
            assertEquals(200, missingTimeline.statusCode(), missingTimeline.body());
            assertTrue(missingTimeline.body().contains("404 Not Found"), missingTimeline.body());
            var missingCsv = request(browser, app, "/timeline.csv?wid=" + workloadId + "&sid=missing");
            assertEquals(200, missingCsv.statusCode(), missingCsv.body());
            assertTrue(missingCsv.body().contains("404 Not Found"), missingCsv.body());
            var missingDownload = request(browser, app, "/download-log.do?id=not-found");
            assertEquals(200, missingDownload.statusCode(), missingDownload.body());
            assertTrue(missingDownload.body().contains("404 Not Found"), missingDownload.body());
            var missingConfig = request(browser, app, "/download-config.do?id=not-found");
            assertEquals(200, missingConfig.statusCode(), missingConfig.body());
            assertTrue(missingConfig.body().contains("404 Not Found"), missingConfig.body());

            var prometheus = request(http, app, "/prometheus");
            assertEquals(200, prometheus.statusCode(), prometheus.body());
            String prometheusContentType = prometheus.headers().firstValue("Content-Type").orElse("");
            assertTrue(prometheusContentType.startsWith("text/plain"), prometheusContentType);
            assertTrue(prometheusContentType.contains("version=0.0.4"), prometheusContentType);
        }
    }

    private void assertStaticResource(HttpClient client, RunningApp app, String path, String contentType)
            throws Exception {
        var response = request(client, app, path);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains(contentType),
                response.headers().toString());
        assertFalse(response.body().isEmpty());
    }

    private void assertNoMultipartTemporaryFiles() throws Exception {
        Path temp = home.resolve("controller-tmp");
        if (!Files.exists(temp)) {
            return;
        }
        try (var paths = Files.walk(temp)) {
            assertFalse(paths.anyMatch(path -> Files.isRegularFile(path)
                    && (path.getFileName().toString().endsWith(".tmp")
                            || path.getFileName().toString().startsWith("upload_"))));
        }
    }

    @Test
    void configurationAssemblesControllerAndLegacyBeanName() throws Exception {
        try (var app = start()) {
            var response = request(app, "/_probe/info");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"name\":\"process-test\""), response.body());
            assertTrue(response.body().contains("\"compatibleBean\":true"), response.body());
        }
    }

    @Test
    void healthyProcessQueuesAndQueriesThroughRealService() throws Exception {
        try (var app = start()) {
            var health = request(app, "/health");
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"accepting\":true"), health.body());
            var submitted = submit(app, 2, false);
            assertEquals(200, submitted.statusCode(), () -> submitted.body() + "\n" + readLog(app));
            assertTrue(submitted.body().contains("\"id\":\"w1\""), submitted.body());
            assertTrue(request(app, "/_probe/workloads/w1").body().contains("QUEUING"));
        }
    }

    @Test
    void workloadDeadlineBoundsDelayStages() throws Exception {
        try (var app = startWithWorkloadTimeout("1s")) {
            long started = System.nanoTime();
            assertEquals(200, submit(app, 8, true).statusCode(), readLog(app));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            String state = "";
            while (System.nanoTime() < deadline) {
                state = request(app, "/_probe/workloads/w1").body();
                if (state.contains("FAILED")) {
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(state.contains("FAILED"), state + "\n" + readLog(app));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4), readLog(app));
            assertTrue(readLog(app).contains("workload deadline expired during delay stage"), readLog(app));
        }
    }

    @Test
    void termClosesAdmissionThenDrainsLocalWorkWithinGracePeriod() throws Exception {
        try (var app = start()) {
            assertEquals(200, submit(app, 3, true).statusCode(), readLog(app));
            assertTrue(request(app, "/_probe/workloads/w1").body().contains("PROCESSING"));
            long stoppedAt = System.nanoTime();
            app.process.destroy();
            assertTrue(app.process.waitFor(6, TimeUnit.SECONDS), readLog(app));
            assertTrue(System.nanoTime() - stoppedAt < TimeUnit.SECONDS.toNanos(6));
            assertEquals(143, app.process.exitValue());
            assertTrue(readLog(app).contains("PROBE_TERM_ADMISSION_REJECTED"), readLog(app));
            assertTrue(Files.readString(home.resolve("archive/run-history.csv")).contains("finished"), readLog(app));
            assertTrue(Files.readString(home.resolve("archive/w1-local-delay/workload-config.xml")).contains("local-delay"));
            try (var released = new ServerSocket(app.port)) {
                assertEquals(app.port, released.getLocalPort());
            }
        }
    }

    private HttpResponse<String> submit(RunningApp app, int seconds, boolean fire) throws Exception {
        String workload = """
                <workload name="local-delay">
                  <auth type="none" /><storage type="none" />
                  <workflow><workstage name="delay" closuredelay="%d">
                    <work type="delay" workers="1" totalOps="1" />
                  </workstage></workflow>
                </workload>
                """.formatted(seconds);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port
                + "/controller/_probe/workloads?fire=" + fire)).header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(workload)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String readLog(RunningApp app) {
        try {
            return Files.readString(app.log);
        } catch (java.io.IOException e) {
            return e.toString();
        }
    }

    private String readSystemLog() {
        try {
            Path log = home.resolve("log/system.log");
            return Files.exists(log) ? Files.readString(log) : "system log not created";
        } catch (java.io.IOException e) {
            return e.toString();
        }
    }

    @Test
    void offLoggingConfigurationStartsWithoutBusinessLogOutput() throws Exception {
        try (var app = start("5s", "OFF")) {
            assertEquals(200, request(app, "/health").statusCode());
            assertEquals("", Files.readString(home.resolve("log/system.log")));
        }
    }

    @ParameterizedTest(name = "{2}: {1}")
    @MethodSource("invalidConfigurations")
    void configurationFailuresHaveDistinctDiagnosticsAndNoRuntimeArtifacts(
            String yaml, List<String> arguments, String diagnostic) throws Exception {
        if (yaml != null) {
            Files.writeString(home.resolve("invalid.yml"), yaml);
        }
        var command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "com.intel.cosbench.app.controller.ControllerApplication"));
        command.addAll(arguments);
        Path log = home.resolve("invalid.log");
        var builder = new ProcessBuilder(command).directory(home.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().remove("COSBENCH_CONFIG");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(8, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains(diagnostic), Files.readString(log));
            assertFalse(Files.exists(home.resolve("archive")));
            assertFalse(Files.exists(home.resolve("log")));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    static java.util.stream.Stream<Arguments> invalidConfigurations() {
        List<String> explicit = List.of("--cosbench.config=invalid.yml");
        String minimal = "schemaVersion: 1\nrole: controller\nname: invalid\npaths: {}\n";
        return java.util.stream.Stream.of(
                Arguments.of(null, List.of(), "DEFAULT_FILE_MISSING"),
                Arguments.of(null, explicit, "EXPLICIT_FILE_MISSING"),
                Arguments.of(null, List.of("--cosbench.config="), "EXPLICIT_FILE_MISSING"),
                Arguments.of(null, List.of("--cosbench.config"), "EXPLICIT_FILE_MISSING"),
                Arguments.of("", explicit, "EMPTY_DOCUMENT"),
                Arguments.of("name: invalid", explicit, "REQUIRED_FIELD_MISSING"),
                Arguments.of(minimal + "concurrency: 0", explicit, "VALUE_OUT_OF_RANGE"),
                Arguments.of(minimal + "listen: [", explicit, "MALFORMED_YAML"),
                Arguments.of(minimal.replace("role: controller", "role: driver"), explicit, "requires role: controller"));
    }

    @Test
    void termInterruptsLocalWorkWhenGracePeriodIsExhausted() throws Exception {
        try (var app = start("750ms", "INFO")) {
            assertEquals(200, submit(app, 30, true).statusCode());
            long stopping = System.nanoTime();
            app.process.destroy();
            assertTrue(app.process.waitFor(2, TimeUnit.SECONDS), readLog(app));
            assertTrue(System.nanoTime() - stopping < TimeUnit.SECONDS.toNanos(2));
            assertEquals(143, app.process.exitValue());
            assertTrue(readLog(app).contains("drain budget exhausted"), readLog(app));
            assertTrue(readLog(app).contains("executor terminated, heartbeat terminated"), readLog(app));
            assertTrue(Files.readString(home.resolve("archive/run-history.csv")).contains("terminated"));
            try (var released = new ServerSocket(app.port)) {
                assertEquals(app.port, released.getLocalPort());
            }
        }
    }

    @Test
    void repeatedServiceCloseRejectsFurtherSubmissionsAndStillAllowsTerm() throws Exception {
        try (var app = start()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port
                    + "/controller/_probe/close")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(503, request(app, "/health").statusCode());
            assertNotEquals(200, submit(app, 1, false).statusCode());
        }
    }

    @Test
    void restartReadsExistingArchiveAndContinuesWorkloadIdsWithoutRewritingHistory() throws Exception {
        try (var first = start()) {
            assertEquals(200, submit(first, 1, true).statusCode());
        }
        Path archive = home.resolve("archive");
        byte[] history = Files.readAllBytes(archive.resolve("run-history.csv"));
        byte[] workload = Files.readAllBytes(archive.resolve("w1-local-delay/workload-config.xml"));
        try (var restarted = start()) {
            var response = request(restarted, "/_probe/archive");
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"id\":\"w1\""), response.body());
            assertTrue(response.body().contains("FINISHED"), response.body());
            assertTrue(response.body().contains("local-delay"), response.body());
            assertArrayEquals(history, Files.readAllBytes(archive.resolve("run-history.csv")));
            assertArrayEquals(workload, Files.readAllBytes(archive.resolve("w1-local-delay/workload-config.xml")));
            assertTrue(submit(restarted, 1, false).body().contains("\"id\":\"w2\""));
        }
    }

    @Test
    void configuredInstallationKeepsArchivesLogsAndMarkersOutsideLaunchDirectory() throws Exception {
        try (var app = start()) {
            assertEquals(200, submit(app, 1, true).statusCode());
        }
        assertTrue(Files.exists(home.resolve("start")));
        assertTrue(Files.exists(home.resolve("stop")));
        assertTrue(Files.exists(home.resolve("archive/w1-local-delay/workload-config.xml")));
        assertTrue(Files.exists(home.resolve("log/system.log")));
        assertFalse(Files.exists(home.resolve("launch/archive")));
        assertFalse(Files.exists(home.resolve("launch/start")));
        assertFalse(Files.exists(home.resolve("launch/stop")));
    }

    @Test
    void disabledWebRunsTheControllerWithoutOpeningAListener() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path config = home.resolve("headless.yml");
        Files.writeString(config, "schemaVersion: 1\nrole: controller\nname: headless\npaths: {}\n"
                + "listen: {port: " + port + "}\nweb: {enabled: false}\n");
        Path log = home.resolve("headless.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "com.intel.cosbench.app.controller.ControllerApplication", "--cosbench.config=" + config)
                .directory(home.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var app = new RunningApp(process, port, log)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (process.isAlive() && System.nanoTime() < deadline
                    && !readLog(app).contains("Started ControllerApplication")) {
                Thread.sleep(20);
            }
            assertTrue(process.isAlive(), readLog(app));
            assertTrue(readLog(app).contains("Started ControllerApplication"), readLog(app));
            try (var listenerNotOpened = new ServerSocket(port)) {
                assertEquals(port, listenerNotOpened.getLocalPort());
            }
        }
        assertEquals(143, process.exitValue());
    }

    @Test
    void restartKeepsIdsWhenQueuedWorkIsCancelledBeforeOlderWorkFinishes() throws Exception {
        try (var first = start()) {
            assertEquals(200, submit(first, 2, true).statusCode());
            assertTrue(submit(first, 1, false).body().contains("\"id\":\"w2\""));
        }
        try (var restarted = start()) {
            var submitted = submit(restarted, 1, false);
            assertTrue(submitted.body().contains("\"id\":\"w3\""), submitted.body());
        }
    }

    @Test
    void termUsesOneGraceBudgetForPendingHttpAndLocalWork() throws Exception {
        try (var app = start("1s", "INFO"); var pending = new java.net.Socket("127.0.0.1", app.port)) {
            assertEquals(200, submit(app, 30, true).statusCode());
            var output = pending.getOutputStream();
            output.write(("POST /controller/_probe/workloads HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Type: application/xml\r\nContent-Length: 6000\r\n\r\n<workload")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.flush();
            Thread.sleep(150); // Let Tomcat begin reading the deliberately incomplete request body.
            long stopping = System.nanoTime();
            app.process.destroy();
            assertTrue(app.process.waitFor(1700, TimeUnit.MILLISECONDS),
                    () -> "Shutdown spent more than one grace budget:\n" + readLog(app));
            assertTrue(System.nanoTime() - stopping < TimeUnit.MILLISECONDS.toNanos(1700));
            assertEquals(143, app.process.exitValue());
            assertTrue(readLog(app).contains("executor terminated, heartbeat terminated"), readLog(app));
            assertTrue(Files.readString(home.resolve("archive/run-history.csv")).contains("terminated"));
            String config = Files.readString(home.resolve("archive/w1-local-delay/workload-config.xml"));
            assertTrue(config.contains("local-delay"), config);
            assertTrue(Files.isRegularFile(home.resolve("archive/w1-local-delay/workload.log")));
            assertEquals("1", Files.readString(home.resolve("archive/.meta")).trim());
        }
    }

    @ParameterizedTest
    @MethodSource("logPaths")
    void yamlLogPathsAndExplicitLoggingFileFollowSharedPrecedence(
            String pathLogs, String loggingFile, String expectedFile) throws Exception {
        try (var app = start("5s", "INFO", pathLogs, loggingFile)) {
            assertEquals(200, request(app, "/health").statusCode());
            assertTrue(Files.isRegularFile(home.resolve(expectedFile)), readLog(app));
            assertFalse(Files.exists(home.resolve("log/system.log")));
        }
    }

    static java.util.stream.Stream<Arguments> logPaths() {
        return java.util.stream.Stream.of(
                Arguments.of("selected-log/custom.log", null, "selected-log/custom.log"),
                Arguments.of("selected-log/custom.log", "override/app.log", "override/app.log"));
    }

    private RunningApp start() throws Exception {
        return start("5s", "INFO");
    }

    private RunningApp start(String gracePeriod, String loggingLevel) throws Exception {
        return start(gracePeriod, loggingLevel, "log/system.log", null);
    }

    private RunningApp start(String gracePeriod, String loggingLevel, String pathLogs, String loggingFile) throws Exception {
        return start(gracePeriod, loggingLevel, pathLogs, loggingFile, "1h");
    }

    private RunningApp startWithWorkloadTimeout(String workloadTimeout) throws Exception {
        return start("5s", "INFO", "log/system.log", null, workloadTimeout);
    }

    private RunningApp start(String gracePeriod, String loggingLevel, String pathLogs, String loggingFile,
            String workloadTimeout) throws Exception {
        return start(gracePeriod, loggingLevel, pathLogs, loggingFile, workloadTimeout,
                "/controller", 10L * 1024 * 1024, "30m");
    }

    private RunningApp startWithWebConfiguration(String contextPath, long maxUploadBytes,
            String sessionTimeout) throws Exception {
        return start("5s", "INFO", "log/system.log", null, "1h",
                contextPath, maxUploadBytes, sessionTimeout);
    }

    private RunningApp start(String gracePeriod, String loggingLevel, String pathLogs, String loggingFile,
            String workloadTimeout, String contextPath, long maxUploadBytes, String sessionTimeout) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path conf = Files.createDirectories(home.resolve("conf")).resolve("controller.yml");
        Path multipartTemp = Files.createDirectories(home.resolve("controller-tmp"));
        Files.writeString(conf, """
                schemaVersion: 1
                role: controller
                name: process-test
                listen:
                  port: %d
                  contextPath: "%s"
                paths:
                  logs: "%s"
                logging:
                  level: "%s"
                  %s
                web:
                  maxUploadBytes: %d
                http:
                  timeouts:
                    command: 1s
                    workload: %s
                shutdown:
                  gracePeriod: %s
                """.formatted(port, contextPath, pathLogs, loggingLevel,
                        loggingFile == null ? "" : "file: \"" + loggingFile + "\"",
                        maxUploadBytes, workloadTimeout, gracePeriod));
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED",
                "-Djava.io.tmpdir=" + multipartTemp,
                "-cp", System.getProperty("java.class.path"),
                "com.intel.cosbench.app.controller.ControllerApplication",
                "--cosbench.config=" + conf,
                "--server.servlet.session.timeout=" + sessionTimeout,
                "--spring.main.sources=" + ControllerProcessProbe.ProbeConfiguration.class.getName()));
        Path log = home.resolve("process.log");
        var process = new ProcessBuilder(command).directory(Files.createDirectories(home.resolve("launch")).toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var app = new RunningApp(process, port, log, contextPath);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                if (request(app, "/_probe/info").statusCode() == 200) {
                    return app;
                }
            } catch (java.io.IOException ignored) {
            }
            Thread.sleep(50);
        }
        app.close();
        fail("Controller did not become ready:\n" + Files.readString(log));
        return app;
    }

    private HttpResponse<String> request(RunningApp app, String path) throws Exception {
        return request(http, app, path);
    }

    private HttpResponse<String> request(HttpClient client, RunningApp app, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port + app.contextPath + path))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(HttpClient client, RunningApp app, String path, String form)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port + app.contextPath + path))
                .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMultipart(HttpClient client, RunningApp app, String path, byte[] content)
            throws Exception {
        String boundary = "cosbench-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"config\"; filename=\"workload.xml\"\r\n"
                + "Content-Type: application/xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port + app.contextPath + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void awaitArchived(RunningApp app, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var archive = request(app, "/_probe/archive/" + id);
            if (archive.statusCode() == 200 && archive.body().contains(id)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("workload was not archived: " + id + "\n" + readLog(app));
    }

    private static String actionWorkload() {
        return "<workload name=\"web-actions\"><auth type=\"none\"/><storage type=\"none\"/>"
                + "<workflow><workstage name=\"wait\" closuredelay=\"0\"><work type=\"delay\" "
                + "workers=\"1\" totalOps=\"1\"/></workstage></workflow></workload>";
    }

    private record RunningApp(Process process, int port, Path log, String contextPath) implements AutoCloseable {
        private RunningApp(Process process, int port, Path log) {
            this(process, port, log, "/controller");
        }

        @Override public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                fail("Controller did not exit after TERM:\n" + Files.readString(log));
            }
        }
    }
}
