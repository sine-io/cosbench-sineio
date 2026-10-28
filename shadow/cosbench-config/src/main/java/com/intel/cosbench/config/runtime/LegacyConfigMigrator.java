package com.intel.cosbench.config.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Converts the supported legacy Controller/Driver INI boundary without modifying its source file. */
public final class LegacyConfigMigrator {

    private LegacyConfigMigrator() {
    }

    public static MigrationReport convert(Path source, Path destination, Path reportPath) {
        String sourceHash = null;
        List<String> mapped = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String role = "unknown";
        String destinationHash = null;
        try {
            if (source == null || !Files.isRegularFile(source)) {
                diagnostics.add(ConfigProblemCode.EXPLICIT_FILE_MISSING.name().toLowerCase(Locale.ROOT));
                return finish(source, destination, reportPath, role, sourceHash, destinationHash, mapped, unmapped,
                        diagnostics, false);
            }
            byte[] sourceBytes = Files.readAllBytes(source);
            sourceHash = sha256(sourceBytes);
            IniDocument ini = IniDocument.parse(new String(sourceBytes, StandardCharsets.UTF_8));
            unmapped.addAll(ini.malformedLines());
            if (ini.sections().containsKey("controller")) {
                role = "controller";
            } else if (ini.sections().containsKey("driver")) {
                role = "driver";
            } else {
                unmapped.add("<document>: missing [controller] or [driver] section");
            }
            validateSections(ini, role, unmapped);
            Map<String, Object> result = role.equals("controller")
                    ? controller(ini, mapped, unmapped, diagnostics)
                    : role.equals("driver") ? driver(ini, mapped, unmapped, diagnostics) : Map.of();

            if (destination != null && Files.exists(destination)) {
                diagnostics.add(ConfigProblemCode.DESTINATION_EXISTS.name().toLowerCase(Locale.ROOT));
            } else if (unmapped.isEmpty() && diagnostics.isEmpty()) {
                writeYaml(destination, result);
                try {
                    VersionedConfigLoader.load(destination, ignored -> null);
                    destinationHash = sha256(Files.readAllBytes(destination));
                } catch (ConfigLoadException e) {
                    Files.deleteIfExists(destination);
                    diagnostics.add("generated_config_invalid: "
                            + e.code().name().toLowerCase(Locale.ROOT) + ": " + safeMessage(e));
                }
            }
        } catch (IOException | RuntimeException e) {
            diagnostics.add("io_error: " + safeMessage(e));
        }
        if (!unmapped.isEmpty() && diagnostics.stream().noneMatch(value -> value.startsWith("legacy_unmapped"))) {
            diagnostics.add("legacy_unmapped: " + unmapped.size() + " value(s) require review");
        }
        boolean success = !role.equals("unknown") && unmapped.isEmpty() && diagnostics.isEmpty()
                && destinationHash != null;
        return finish(source, destination, reportPath, role, sourceHash, destinationHash, mapped, unmapped,
                diagnostics, success);
    }

    private static Map<String, Object> controller(IniDocument ini, List<String> mapped, List<String> unmapped,
            List<String> diagnostics) {
        Map<String, String> values = ini.section("controller");
        Endpoint endpoint = endpoint(values.get("url"), 19088, "/controller", "controller.url", mapped, unmapped);
        Map<String, Object> root = base("controller", values.getOrDefault("name", "Controller"), endpoint, mapped);
        Map<String, Object> paths = map();
        paths.put("workspace", pathValue(values, "workspace_dir", "workspace", mapped));
        paths.put("archive", pathValue(values, "archive_dir", "archive", mapped));
        paths.put("logs", pathValue(values, "log_file", "log/system.log", mapped));
        root.put("paths", paths);
        root.put("drivers", controllerDrivers(ini, values, mapped, unmapped, diagnostics));
        commonValues(root, values, mapped, unmapped);
        Map<String, Object> web = map();
        web.put("enabled", true);
        web.put("maxUploadBytes", 10L * 1024 * 1024);
        root.put("web", web);
        return root;
    }

    private static Map<String, Object> driver(IniDocument ini, List<String> mapped, List<String> unmapped,
            List<String> diagnostics) {
        Map<String, String> values = ini.section("driver");
        Endpoint endpoint = endpoint(values.get("url"), 18088, "/driver", "driver.url", mapped, unmapped);
        Map<String, Object> root = base("driver", values.getOrDefault("name", "driver1"), endpoint, mapped);
        Map<String, Object> paths = map();
        paths.put("workspace", pathValue(values, "workspace_dir", "workspace", mapped));
        paths.put("archive", pathValue(values, "archive_dir", "archive", mapped));
        paths.put("mission", pathValue(values, "mission_dir", "log/mission", mapped));
        paths.put("logs", pathValue(values, "log_file", "log/system.log", mapped));
        root.put("paths", paths);
        root.put("drivers", List.of());
        commonValues(root, values, mapped, unmapped);
        Map<String, Object> web = map();
        web.put("enabled", true);
        web.put("maxUploadBytes", 10L * 1024 * 1024);
        root.put("web", web);
        return root;
    }

    private static Map<String, Object> base(String role, String name, Endpoint endpoint, List<String> mapped) {
        Map<String, Object> root = map();
        root.put("schemaVersion", 1);
        root.put("role", role);
        root.put("name", name);
        Map<String, Object> listen = map();
        listen.put("host", endpoint.host());
        listen.put("port", endpoint.port());
        listen.put("contextPath", endpoint.contextPath());
        root.put("listen", listen);
        mapped.add(role + ".name");
        mapped.add(role + ".url -> listen");
        return root;
    }

    private static void commonValues(Map<String, Object> root, Map<String, String> values, List<String> mapped,
            List<String> unmapped) {
        int concurrency = integerValue(values, "concurrency", 1, mapped, unmapped);
        root.put("concurrency", concurrency);
        Map<String, Object> logging = map();
        logging.put("level", values.getOrDefault("log_level", "INFO"));
        logging.put("file", values.getOrDefault("log_file", "log/system.log"));
        root.put("logging", logging);
        mapped.add("log_level -> logging.level");
        mapped.add("log_file -> logging.file");
        Map<String, Object> provider = map();
        provider.put("name", "mock");
        root.put("provider", provider);
        Map<String, Object> auth = map();
        Map<String, Object> tls = map();
        tls.put("enabled", false);
        auth.put("tls", tls);
        root.put("auth", auth);
        Map<String, Object> http = map();
        Map<String, Object> pool = map();
        pool.put("maxConnections", 32);
        pool.put("maxPerRoute", 8);
        pool.put("idleTimeout", "30s");
        http.put("pool", pool);
        Map<String, Object> timeouts = map();
        timeouts.put("connect", "2s");
        timeouts.put("read", "30s");
        timeouts.put("command", "60s");
        timeouts.put("workload", "1h");
        http.put("timeouts", timeouts);
        Map<String, Object> retries = map();
        retries.put("maxAttempts", 1);
        retries.put("backoff", "0ms");
        http.put("retries", retries);
        root.put("http", http);
        Map<String, Object> shutdown = map();
        shutdown.put("gracePeriod", "30s");
        root.put("shutdown", shutdown);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!switch (entry.getKey()) {
                case "name", "url", "drivers", "log_level", "log_file", "archive_dir", "workspace_dir", "mission_dir",
                        "concurrency" -> true;
                default -> false;
            }) {
                unmapped.add(entry.getKey());
            }
        }
    }

    private static List<Map<String, Object>> controllerDrivers(IniDocument ini, Map<String, String> controller,
            List<String> mapped, List<String> unmapped, List<String> diagnostics) {
        int count = integerValue(controller, "drivers", 0, mapped, unmapped);
        if (count < 0) {
            unmapped.add("controller.drivers=" + count + " (expected non-negative integer)");
            count = 0;
        } else if (count > 10000) {
            unmapped.add("controller.drivers=" + count + " (maximum supported count is 10000)");
            count = 0;
        }
        List<Map<String, Object>> drivers = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String sectionName = "driver" + index;
            Map<String, String> values = ini.section(sectionName);
            if (values.isEmpty()) {
                diagnostics.add("missing section [" + sectionName + "]");
                continue;
            }
            String name = values.get("name");
            String url = values.get("url");
            if (name == null || name.isBlank()) {
                unmapped.add(sectionName + ".name");
            }
            if (url == null || url.isBlank()) {
                unmapped.add(sectionName + ".url");
            }
            if (name == null || name.isBlank() || url == null || url.isBlank()) {
                continue;
            }
            Map<String, Object> item = map();
            item.put("name", name);
            item.put("url", url);
            drivers.add(item);
            mapped.add(sectionName + ".name -> drivers[].name");
            mapped.add(sectionName + ".url -> drivers[].url");
            for (String key : values.keySet()) {
                if (!key.equals("name") && !key.equals("url")) {
                    unmapped.add(sectionName + "." + key);
                }
            }
        }
        for (String sectionName : ini.sections().keySet()) {
            if (!sectionName.matches("driver[0-9]+")) {
                continue;
            }
            try {
                int index = Integer.parseInt(sectionName.substring("driver".length()));
                if (index < 1 || index > count || !sectionName.equals("driver" + index)) {
                    unmapped.add("[" + sectionName + "] is outside controller.drivers count " + count);
                }
            } catch (NumberFormatException e) {
                unmapped.add("[" + sectionName + "] has an invalid driver index");
            }
        }
        return drivers;
    }

    private static void validateSections(IniDocument ini, String role, List<String> unmapped) {
        for (Map.Entry<String, Map<String, String>> entry : ini.sections().entrySet()) {
            String section = entry.getKey();
            if (section.isEmpty()) {
                for (String key : entry.getValue().keySet()) {
                    unmapped.add("<document>." + key);
                }
                continue;
            }
            boolean allowed = role.equals("controller")
                    ? section.equals("controller") || section.matches("driver[0-9]+")
                    : role.equals("driver") && section.equals("driver");
            if (!allowed) {
                unmapped.add("[" + section + "]");
            }
        }
    }

    private static String pathValue(Map<String, String> values, String key, String fallback, List<String> mapped) {
        if (values.containsKey(key)) {
            mapped.add(key + " -> paths." + switch (key) {
                case "workspace_dir" -> "workspace";
                case "archive_dir" -> "archive";
                case "mission_dir" -> "mission";
                case "log_file" -> "logs";
                default -> key;
            });
        }
        return values.getOrDefault(key, fallback);
    }

    private static int integerValue(Map<String, String> values, String key, int fallback, List<String> mapped,
            List<String> unmapped) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            mapped.add(key + " -> " + key);
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            unmapped.add(key + "=" + value + " (expected integer)");
            return fallback;
        }
    }

    private static Endpoint endpoint(String raw, int defaultPort, String defaultContext, String field,
            List<String> mapped, List<String> unmapped) {
        if (raw == null || raw.isBlank()) {
            return new Endpoint("127.0.0.1", defaultPort, defaultContext);
        }
        try {
            java.net.URI uri = java.net.URI.create(raw.trim());
            if (uri.getHost() == null || uri.getScheme() == null
                    || (!uri.getScheme().equalsIgnoreCase("http")
                            && !uri.getScheme().equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("expected an absolute HTTP(S) URL");
            }
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = defaultContext;
            }
            if (path.endsWith("/index.html")) {
                path = path.substring(0, path.length() - "/index.html".length());
            }
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
            int port = uri.getPort() < 0 ? defaultPort : uri.getPort();
            return new Endpoint(host, port, path.isBlank() ? "/" : path);
        } catch (IllegalArgumentException e) {
            unmapped.add(field + "=" + raw + " (invalid URL)");
            return new Endpoint("127.0.0.1", defaultPort, defaultContext);
        }
    }

    private static MigrationReport finish(Path source, Path destination, Path reportPath, String role,
            String sourceHash, String destinationHash, List<String> mapped, List<String> unmapped,
            List<String> diagnostics, boolean success) {
        MigrationReport result = new MigrationReport(source == null ? null : source.toString(),
                destination == null ? null : destination.toString(), role, sourceHash, destinationHash, mapped,
                unmapped, diagnostics, success);
        if (reportPath != null) {
            try {
                if (reportPath.getParent() != null) {
                    Files.createDirectories(reportPath.getParent());
                }
                Files.writeString(reportPath, reportJson(result), StandardCharsets.UTF_8);
            } catch (IOException e) {
                List<String> reportDiagnostics = new ArrayList<>(result.diagnostics());
                reportDiagnostics.add("report_io_error: " + safeMessage(e));
                result = new MigrationReport(result.source(), result.destination(), result.role(), result.sourceSha256(),
                        result.destinationSha256(), result.mapped(), result.unmapped(), reportDiagnostics, false);
            }
        }
        return result;
    }

    private static void writeYaml(Path destination, Map<String, Object> result) throws IOException {
        if (destination == null) {
            throw new IOException("destination is required");
        }
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        options.setIndent(2);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        Files.writeString(destination, new Yaml(options).dump(result), StandardCharsets.UTF_8);
    }

    private static String reportJson(MigrationReport result) {
        StringBuilder json = new StringBuilder("{\n");
        field(json, "source", result.source(), true);
        field(json, "destination", result.destination(), true);
        field(json, "role", result.role(), true);
        field(json, "source_sha256", result.sourceSha256(), true);
        field(json, "destination_sha256", result.destinationSha256(), true);
        array(json, "mapped", result.mapped(), true);
        array(json, "unmapped", result.unmapped(), true);
        array(json, "diagnostics", result.diagnostics(), true);
        json.append("  \"success\": ").append(result.success()).append('\n').append('}').append('\n');
        return json.toString();
    }

    private static void field(StringBuilder json, String key, String value, boolean comma) {
        json.append("  \"").append(key).append("\": ").append(value == null ? "null" : quote(value));
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void array(StringBuilder json, String key, List<String> values, boolean comma) {
        json.append("  \"").append(key).append("\": [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append(quote(values.get(i)));
        }
        json.append(']');
        if (comma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return "\"" + escaped + "\"";
    }

    private static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static <K, V> Map<K, V> map() {
        return new LinkedHashMap<>();
    }

    private record Endpoint(String host, int port, String contextPath) {
    }

    private static final class IniDocument {
        private final Map<String, Map<String, String>> sections;
        private final List<String> malformedLines;

        private IniDocument(Map<String, Map<String, String>> sections, List<String> malformedLines) {
            this.sections = sections;
            this.malformedLines = malformedLines;
        }

        static IniDocument parse(String content) {
            Map<String, Map<String, String>> sections = new TreeMap<>();
            List<String> malformedLines = new ArrayList<>();
            String current = "";
            sections.put(current, new LinkedHashMap<>());
            int lineNumber = 0;
            for (String line : content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    current = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase(Locale.ROOT);
                    sections.computeIfAbsent(current, ignored -> new LinkedHashMap<>());
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals <= 0) {
                    malformedLines.add("<line " + lineNumber + ">: malformed entry");
                    continue;
                }
                String key = trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                String value = trimmed.substring(equals + 1).trim();
                sections.computeIfAbsent(current, ignored -> new LinkedHashMap<>()).put(key, value);
            }
            return new IniDocument(sections, malformedLines);
        }

        Map<String, Map<String, String>> sections() {
            return sections;
        }

        List<String> malformedLines() {
            return malformedLines;
        }

        Map<String, String> section(String name) {
            return sections.getOrDefault(name, Map.of());
        }
    }
}
