package com.intel.cosbench.config.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

/** Reads the shared schema used by Controller and Driver without exposing YAML maps to the services. */
public final class VersionedConfigLoader {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "role", "name", "listen", "paths", "drivers", "auth", "http", "concurrency",
            "provider", "logging", "web", "shutdown");
    private static final Set<String> LISTEN_KEYS = Set.of("host", "port", "contextPath");
    private static final Set<String> PATH_KEYS = Set.of("workspace", "archive", "mission", "logs");
    private static final Set<String> DRIVER_KEYS = Set.of("name", "url");
    private static final Set<String> AUTH_KEYS = Set.of("token", "tls");
    private static final Set<String> TOKEN_KEYS = Set.of("env", "file");
    private static final Set<String> TLS_KEYS = Set.of("enabled", "keyStore", "trustStore");
    private static final Set<String> HTTP_KEYS = Set.of("pool", "timeouts", "retries");
    private static final Set<String> POOL_KEYS = Set.of("maxConnections", "maxPerRoute", "idleTimeout");
    private static final Set<String> TIMEOUT_KEYS = Set.of("connect", "read", "command", "workload");
    private static final Set<String> RETRY_KEYS = Set.of("maxAttempts", "backoff");
    private static final Set<String> PROVIDER_KEYS = Set.of("name", "concurrency");
    private static final Set<String> LOGGING_KEYS = Set.of("level", "file");
    private static final Set<String> WEB_KEYS = Set.of("enabled", "maxUploadBytes");
    private static final Set<String> SHUTDOWN_KEYS = Set.of("gracePeriod");

    private VersionedConfigLoader() {
    }

    public static ServiceConfig loadDefault(Path baseDirectory, ServiceRole role, SecretResolver resolver) {
        Path path = baseDirectory.resolve("conf").resolve(role.wireName() + ".yml");
        if (!Files.isRegularFile(path)) {
            throw new ConfigLoadException(ConfigProblemCode.DEFAULT_FILE_MISSING,
                    "default " + role.wireName() + " configuration is missing: " + path);
        }
        return load(path, resolver);
    }

    public static ServiceConfig load(Path path, SecretResolver resolver) {
        if (path == null) {
            throw new ConfigLoadException(ConfigProblemCode.EXPLICIT_FILE_MISSING, "configuration path is null");
        }
        if (!Files.isRegularFile(path)) {
            throw new ConfigLoadException(ConfigProblemCode.EXPLICIT_FILE_MISSING,
                    "configuration file is missing: " + path);
        }
        Object document;
        try (InputStream input = Files.newInputStream(path)) {
            document = new Yaml().load(input);
        } catch (Exception e) {
            if (e instanceof ConfigLoadException configLoadException) {
                throw configLoadException;
            }
            throw new ConfigLoadException(ConfigProblemCode.MALFORMED_YAML,
                    "cannot parse YAML configuration " + path + ": " + e.getMessage(), e);
        }
        if (document == null) {
            throw new ConfigValidationException(ConfigProblemCode.EMPTY_DOCUMENT,
                    "configuration document is empty: " + path);
        }
        Map<String, Object> root = map(document, "document");
        checkUnknown(root, ROOT_KEYS, "document");

        int schemaVersion = integer(required(root, "schemaVersion", "document"), "schemaVersion");
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new ConfigValidationException(ConfigProblemCode.UNSUPPORTED_SCHEMA,
                    "unsupported schemaVersion " + schemaVersion + "; supported version is " + SUPPORTED_SCHEMA_VERSION);
        }
        ServiceRole role = role(required(root, "role", "document"));
        String name = text(required(root, "name", "document"), "name");

        Map<String, Object> listenValues = optionalMap(root, "listen", "document");
        checkUnknown(listenValues, LISTEN_KEYS, "listen");
        String defaultHost = "127.0.0.1";
        int defaultPort = role == ServiceRole.CONTROLLER ? 19088 : 18088;
        String defaultContext = "/" + role.wireName();
        ListenConfig listen;
        try {
            listen = new ListenConfig(
                    optionalText(listenValues, "host", defaultHost, "listen.host"),
                    optionalInteger(listenValues, "port", defaultPort, "listen.port"),
                    optionalText(listenValues, "contextPath", defaultContext, "listen.contextPath"));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }

        if (!root.containsKey("paths")) {
            throw new ConfigValidationException(ConfigProblemCode.REQUIRED_FIELD_MISSING,
                    "required configuration section is missing: paths");
        }
        Map<String, Object> pathValues = map(root.get("paths"), "paths");
        checkUnknown(pathValues, PATH_KEYS, "paths");
        ServicePaths paths = new ServicePaths(
                path(pathValues, "workspace", "workspace"),
                path(pathValues, "archive", "archive"),
                path(pathValues, "mission", "mission"),
                path(pathValues, "logs", "log/system.log"));

        List<DriverEndpoint> drivers = drivers(root.get("drivers"));
        AuthConfig auth = auth(root.get("auth"), resolver == null ? SecretResolver.environment() : resolver);
        HttpConfig http = http(root.get("http"));
        int concurrency = optionalInteger(root, "concurrency", 1, "concurrency");
        ProviderConfig provider = provider(root.get("provider"), concurrency);
        LoggingConfig logging = logging(root.get("logging"));
        WebConfig web = web(root.get("web"));
        ShutdownConfig shutdown = shutdown(root.get("shutdown"));

        try {
            return new ServiceConfig(schemaVersion, role, name, listen, paths, drivers, auth, http, concurrency,
                    provider, logging, web, shutdown);
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }
    }

    private static List<DriverEndpoint> drivers(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw validation(ConfigProblemCode.INVALID_TYPE, "drivers must be a YAML list", null);
        }
        List<DriverEndpoint> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            Map<String, Object> driver = map(list.get(index), "drivers[" + index + "]");
            String location = "drivers[" + index + "]";
            checkUnknown(driver, DRIVER_KEYS, location);
            String name = text(required(driver, "name", location), location + ".name");
            String url = text(required(driver, "url", location), location + ".url");
            if (!names.add(name)) {
                throw validation(ConfigProblemCode.INVALID_VALUE, "duplicate driver name: " + name, null);
            }
            try {
                result.add(new DriverEndpoint(name, url));
            } catch (IllegalArgumentException e) {
                throw validation(ConfigProblemCode.INVALID_VALUE, e.getMessage(), e);
            }
        }
        return List.copyOf(result);
    }

    private static AuthConfig auth(Object value, SecretResolver resolver) {
        Map<String, Object> values = optionalMapValue(value, "auth");
        checkUnknown(values, AUTH_KEYS, "auth");
        TokenConfig token = null;
        if (values.containsKey("token")) {
            Map<String, Object> tokenValues = map(values.get("token"), "auth.token");
            checkUnknown(tokenValues, TOKEN_KEYS, "auth.token");
            String env = optionalNullableText(tokenValues, "env", "auth.token.env");
            String file = optionalNullableText(tokenValues, "file", "auth.token.file");
            if ((env == null) == (file == null)) {
                throw validation(ConfigProblemCode.INVALID_VALUE,
                        "auth.token must contain exactly one env or file reference", null);
            }
            String reference = env != null ? env : file;
            String secret;
            try {
                secret = resolver.resolve(reference);
            } catch (RuntimeException e) {
                secret = null;
            }
            if (secret == null || secret.isBlank()) {
                throw validation(ConfigProblemCode.SECRET_UNRESOLVED,
                        "auth.token reference cannot be resolved: " + (env != null ? "env:" + env : "file:" + file), null);
            }
            try {
                token = new TokenConfig(env, file, secret);
            } catch (IllegalArgumentException e) {
                throw validation(ConfigProblemCode.INVALID_VALUE, e.getMessage(), e);
            }
        }
        Map<String, Object> tlsValues = optionalMapValue(values.get("tls"), "auth.tls");
        checkUnknown(tlsValues, TLS_KEYS, "auth.tls");
        boolean enabled = optionalBoolean(tlsValues, "enabled", false, "auth.tls.enabled");
        String keyStore = optionalNullableText(tlsValues, "keyStore", "auth.tls.keyStore");
        String trustStore = optionalNullableText(tlsValues, "trustStore", "auth.tls.trustStore");
        try {
            return new AuthConfig(token, new TlsConfig(enabled, keyStore, trustStore));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.INVALID_VALUE, e.getMessage(), e);
        }
    }

    private static HttpConfig http(Object value) {
        Map<String, Object> values = optionalMapValue(value, "http");
        checkUnknown(values, HTTP_KEYS, "http");
        Map<String, Object> poolValues = optionalMapValue(values.get("pool"), "http.pool");
        checkUnknown(poolValues, POOL_KEYS, "http.pool");
        int maxConnections = optionalInteger(poolValues, "maxConnections", 32, "http.pool.maxConnections");
        int maxPerRoute = optionalInteger(poolValues, "maxPerRoute", Math.min(8, maxConnections),
                "http.pool.maxPerRoute");
        Duration idleTimeout = optionalDuration(poolValues, "idleTimeout", Duration.ofSeconds(30),
                "http.pool.idleTimeout");

        Map<String, Object> timeoutValues = optionalMapValue(values.get("timeouts"), "http.timeouts");
        checkUnknown(timeoutValues, TIMEOUT_KEYS, "http.timeouts");
        HttpTimeoutConfig timeouts;
        try {
            timeouts = new HttpTimeoutConfig(
                    optionalDuration(timeoutValues, "connect", Duration.ofSeconds(2), "http.timeouts.connect"),
                    optionalDuration(timeoutValues, "read", Duration.ofSeconds(30), "http.timeouts.read"),
                    optionalDuration(timeoutValues, "command", Duration.ofSeconds(60), "http.timeouts.command"),
                    optionalDuration(timeoutValues, "workload", Duration.ofHours(1), "http.timeouts.workload"));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }

        Map<String, Object> retryValues = optionalMapValue(values.get("retries"), "http.retries");
        checkUnknown(retryValues, RETRY_KEYS, "http.retries");
        try {
            return new HttpConfig(
                    new HttpPoolConfig(maxConnections, maxPerRoute, idleTimeout),
                    timeouts,
                    new RetryConfig(optionalInteger(retryValues, "maxAttempts", 1, "http.retries.maxAttempts"),
                            optionalDuration(retryValues, "backoff", Duration.ZERO, "http.retries.backoff")));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }
    }

    private static ProviderConfig provider(Object value, int concurrency) {
        Map<String, Object> values = optionalMapValue(value, "provider");
        checkUnknown(values, PROVIDER_KEYS, "provider");
        try {
            return new ProviderConfig(optionalText(values, "name", "mock", "provider.name"),
                    optionalInteger(values, "concurrency", concurrency, "provider.concurrency"));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }
    }

    private static LoggingConfig logging(Object value) {
        Map<String, Object> values = optionalMapValue(value, "logging");
        checkUnknown(values, LOGGING_KEYS, "logging");
        try {
            return new LoggingConfig(optionalText(values, "level", "INFO", "logging.level"),
                    Path.of(optionalText(values, "file", "log/system.log", "logging.file")));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }
    }

    private static WebConfig web(Object value) {
        Map<String, Object> values = optionalMapValue(value, "web");
        checkUnknown(values, WEB_KEYS, "web");
        try {
            return new WebConfig(optionalBoolean(values, "enabled", true, "web.enabled"),
                    optionalLong(values, "maxUploadBytes", 10L * 1024 * 1024, "web.maxUploadBytes"));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }
    }

    private static ShutdownConfig shutdown(Object value) {
        Map<String, Object> values = optionalMapValue(value, "shutdown");
        checkUnknown(values, SHUTDOWN_KEYS, "shutdown");
        try {
            return new ShutdownConfig(optionalDuration(values, "gracePeriod", Duration.ofSeconds(30),
                    "shutdown.gracePeriod"));
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, e.getMessage(), e);
        }
    }

    private static ServiceRole role(Object value) {
        try {
            return ServiceRole.parse(value);
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.INVALID_VALUE, e.getMessage(), e);
        }
    }

    private static Path path(Map<String, Object> values, String key, String fallback) {
        String value = optionalText(values, key, fallback, "paths." + key);
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw validation(ConfigProblemCode.INVALID_VALUE, "paths." + key + " is not a valid path", e);
        }
    }

    private static Duration optionalDuration(Map<String, Object> values, String key, Duration fallback, String field) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return parseDuration(value);
        } catch (IllegalArgumentException e) {
            throw validation(ConfigProblemCode.INVALID_VALUE, field + " is not a duration: " + value, e);
        }
    }

    static Duration parseDuration(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("duration must not be null");
        }
        if (value instanceof Number number) {
            if (!isIntegral(number)) {
                throw new IllegalArgumentException("numeric duration must be an integer number of milliseconds");
            }
            long millis;
            try {
                millis = number instanceof BigInteger bigInteger ? bigInteger.longValueExact() : number.longValue();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("duration is too large", e);
            }
            if (millis < 0) {
                throw new IllegalArgumentException("duration cannot be negative");
            }
            return Duration.ofMillis(millis);
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s|m|h|d)$")
                .matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected a number followed by ns/us/ms/s/m/h/d");
        }
        double amount = Double.parseDouble(matcher.group(1));
        double nanos = switch (matcher.group(2)) {
            case "ns" -> amount;
            case "us", "µs" -> amount * 1_000;
            case "ms" -> amount * 1_000_000;
            case "s" -> amount * 1_000_000_000;
            case "m" -> amount * 60_000_000_000L;
            case "h" -> amount * 3_600_000_000_000L;
            case "d" -> amount * 86_400_000_000_000L;
            default -> throw new IllegalArgumentException("unsupported duration unit");
        };
        if (Double.isInfinite(nanos) || nanos > Long.MAX_VALUE) {
            throw new IllegalArgumentException("duration is too large");
        }
        return Duration.ofNanos((long) nanos);
    }

    private static Map<String, Object> optionalMap(Map<String, Object> root, String key, String location) {
        if (!root.containsKey(key)) {
            return new LinkedHashMap<>();
        }
        return map(root.get(key), location);
    }

    private static Map<String, Object> optionalMapValue(Object value, String location) {
        return value == null ? new LinkedHashMap<>() : map(value, location);
    }

    private static Map<String, Object> map(Object value, String location) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw validation(ConfigProblemCode.INVALID_TYPE, location + " must be a YAML mapping", null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw validation(ConfigProblemCode.INVALID_TYPE, location + " contains a non-string key", null);
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void checkUnknown(Map<String, Object> values, Set<String> allowed, String location) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw validation(ConfigProblemCode.UNKNOWN_KEY, "unknown configuration key " + location + "." + key, null);
            }
        }
    }

    private static Object required(Map<String, Object> values, String key, String location) {
        if (!values.containsKey(key) || values.get(key) == null) {
            throw validation(ConfigProblemCode.REQUIRED_FIELD_MISSING, "required configuration field is missing: " + location + "." + key,
                    null);
        }
        return values.get(key);
    }

    private static String optionalText(Map<String, Object> values, String key, String fallback, String field) {
        return values.containsKey(key) ? text(values.get(key), field) : fallback;
    }

    private static String optionalNullableText(Map<String, Object> values, String key, String field) {
        return values.containsKey(key) && values.get(key) != null ? text(values.get(key), field) : null;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String)) {
            throw validation(ConfigProblemCode.INVALID_TYPE, field + " must be a scalar", null);
        }
        String result = value.toString().trim();
        if (result.isEmpty()) {
            throw validation(ConfigProblemCode.INVALID_VALUE, field + " must not be empty", null);
        }
        return result;
    }

    private static int optionalInteger(Map<String, Object> values, String key, int fallback, String field) {
        return values.containsKey(key) ? integer(values.get(key), field) : fallback;
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Number number) || !isIntegral(number)) {
            throw validation(ConfigProblemCode.INVALID_TYPE, field + " must be an integer", null);
        }
        long result;
        try {
            result = number instanceof BigInteger bigInteger ? bigInteger.longValueExact()
                    : number instanceof BigDecimal decimal ? decimal.longValueExact() : number.longValue();
        } catch (ArithmeticException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, field + " is outside integer range", e);
        }
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, field + " is outside integer range", null);
        }
        return (int) result;
    }

    private static long optionalLong(Map<String, Object> values, String key, long fallback, String field) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        Object value = values.get(key);
        if (!(value instanceof Number number) || !isIntegral(number)) {
            throw validation(ConfigProblemCode.INVALID_TYPE, field + " must be an integer", null);
        }
        try {
            return number instanceof BigInteger bigInteger ? bigInteger.longValueExact()
                    : number instanceof BigDecimal decimal ? decimal.longValueExact() : number.longValue();
        } catch (ArithmeticException e) {
            throw validation(ConfigProblemCode.VALUE_OUT_OF_RANGE, field + " is outside long range", e);
        }
    }

    private static boolean optionalBoolean(Map<String, Object> values, String key, boolean fallback, String field) {
        if (!values.containsKey(key)) {
            return fallback;
        }
        Object value = values.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw validation(ConfigProblemCode.INVALID_TYPE, field + " must be true or false", null);
    }

    private static boolean isIntegral(Number number) {
        return number instanceof Byte || number instanceof Short || number instanceof Integer
                || number instanceof Long || number instanceof BigInteger;
    }

    private static ConfigValidationException validation(ConfigProblemCode code, String message, Throwable cause) {
        return cause == null ? new ConfigValidationException(code, message) : new ConfigValidationException(code, message, cause);
    }
}
