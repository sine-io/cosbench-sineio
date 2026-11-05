package com.intel.cosbench.operator;

import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.intel.cosbench.config.runtime.ConfigLoadException;
import com.intel.cosbench.config.runtime.ControllerDriverConfigBinder;
import com.intel.cosbench.config.runtime.DriverInstanceConfigGenerator;
import com.intel.cosbench.config.runtime.LegacyConfigMigrator;
import com.intel.cosbench.config.runtime.MigrationSecuritySettings;
import com.intel.cosbench.config.runtime.MigrationReport;
import com.intel.cosbench.config.runtime.SecretResolver;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.ServiceRole;
import com.intel.cosbench.config.runtime.VersionedConfigLoader;

/** Small process boundary used by the stable operator shell commands. */
public final class OperatorCli {

    private OperatorCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || hasHelp(args)) {
            usage(out);
            return args.length == 0 ? 2 : 0;
        }
        try {
            return switch (args[0]) {
                case "migrate" -> migrate(args, out, err);
                case "inspect" -> inspect(args, out, err);
                case "expand-drivers" -> expandDrivers(args, out, err);
                case "bind-controller" -> bindController(args, out, err);
                default -> {
                    err.println("unknown command: " + args[0]);
                    usage(err);
                    yield 2;
                }
            };
        } catch (UsageException e) {
            err.println("usage error: " + e.getMessage());
            usage(err);
            return 2;
        } catch (ConfigLoadException e) {
            err.println("configuration failed [" + e.code().name().toLowerCase(java.util.Locale.ROOT) + "]: "
                    + e.getMessage());
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("command failed: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("command failed: " + e.getMessage());
            return 1;
        }
    }

    private static int migrate(String[] args, PrintStream out, PrintStream err) {
        MigrationOptions options = migrationOptions(args);
        if (options == null) {
            err.println("migrate requires --source and --destination");
            usage(err);
            return 2;
        }
        Path report = options.report() == null
                ? Path.of(options.destination().toString() + ".report.json")
                : options.report();
        MigrationReport result = LegacyConfigMigrator.convert(options.source(), options.destination(), report,
                options.security());
        out.println("migration=" + (result.success() ? "passed" : "failed"));
        out.println("role=" + result.role());
        out.println("source_sha256=" + nullToEmpty(result.sourceSha256()));
        out.println("destination_sha256=" + nullToEmpty(result.destinationSha256()));
        out.println("mapped=" + result.mapped().size());
        out.println("unmapped=" + result.unmapped().size());
        out.println("report=" + report.toAbsolutePath().normalize());
        result.diagnostics().forEach(value -> err.println("diagnostic=" + value));
        result.unmapped().forEach(value -> err.println("unmapped=" + value));
        return result.success() ? 0 : 1;
    }

    private static int inspect(String[] args, PrintStream out, PrintStream err) throws Exception {
        Arguments parsed = Arguments.parse(args, 1, "--config", "--role", "--check-port");
        Path configPath = requiredPath(parsed.values(), "--config");
        ServiceConfig config = VersionedConfigLoader.load(configPath, SecretResolver.environment());
        String expectedRole = parsed.values().get("--role");
        if (expectedRole != null && config.role() != ServiceRole.parse(expectedRole)) {
            throw new IllegalArgumentException("configuration role is " + config.role().wireName()
                    + ", expected " + expectedRole);
        }
        if (parsed.flags().contains("--check-port") && config.web().enabled()) {
            checkPortAvailable(config);
        }
        out.printf("%s\t%s\t%d\t%d\t%s%n", config.role().wireName(), config.listen().host(),
                config.listen().port(), config.shutdown().gracePeriod().toMillis(), config.web().enabled());
        return 0;
    }

    private static int expandDrivers(String[] args, PrintStream out, PrintStream err) {
        Arguments parsed = Arguments.parse(args, 1, "--config", "--output-dir", "--count");
        Path source = requiredPath(parsed.values(), "--config");
        Path outputDirectory = requiredPath(parsed.values(), "--output-dir");
        int count;
        try {
            count = Integer.parseInt(required(parsed.values(), "--count"));
        } catch (NumberFormatException e) {
            throw new UsageException("--count must be an integer");
        }
        DriverInstanceConfigGenerator.generate(source, outputDirectory, count, SecretResolver.environment())
                .forEach(path -> out.println(path.toAbsolutePath().normalize()));
        return 0;
    }

    private static int bindController(String[] args, PrintStream out, PrintStream err) {
        Arguments parsed = Arguments.parse(args, 1, "--config", "--drivers-dir", "--output", "--count");
        Path output = ControllerDriverConfigBinder.bind(
                requiredPath(parsed.values(), "--config"),
                requiredPath(parsed.values(), "--drivers-dir"),
                integerValue(parsed.values(), "--count"),
                requiredPath(parsed.values(), "--output"),
                SecretResolver.environment());
        out.println(output.toAbsolutePath().normalize());
        return 0;
    }

    private static int integerValue(Map<String, String> values, String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException e) {
            throw new UsageException(key + " must be an integer");
        }
    }

    private static void checkPortAvailable(ServiceConfig config) throws Exception {
        InetAddress address = InetAddress.getByName(config.listen().host());
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(address, config.listen().port()), 1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("listen port is unavailable: " + config.listen().host() + ":"
                    + config.listen().port(), e);
        }
    }

    private static MigrationOptions migrationOptions(String[] args) {
        if (args.length == 4 && !args[1].startsWith("--")) {
            return new MigrationOptions(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                    MigrationSecuritySettings.defaults());
        }
        Arguments parsed = Arguments.parse(args, 1, "--source", "--destination", "--report",
                "--service-token-env", "--driver-key-store", "--controller-trust-store");
        String source = parsed.values().get("--source");
        String destination = parsed.values().get("--destination");
        if (source == null || destination == null) {
            return null;
        }
        String report = parsed.values().get("--report");
        MigrationSecuritySettings defaults = MigrationSecuritySettings.defaults();
        MigrationSecuritySettings security;
        try {
            security = new MigrationSecuritySettings(
                    parsed.values().getOrDefault("--service-token-env", defaults.serviceTokenEnv()),
                    parsed.values().getOrDefault("--driver-key-store", defaults.driverKeyStore()),
                    parsed.values().getOrDefault("--controller-trust-store", defaults.controllerTrustStore()));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        return new MigrationOptions(Path.of(source), Path.of(destination), report == null ? null : Path.of(report),
                security);
    }

    private static Path requiredPath(Map<String, String> values, String key) {
        return Path.of(required(values, key));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new UsageException(key + " is required");
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasHelp(String[] args) {
        return java.util.Arrays.stream(args).anyMatch(value -> value.equals("--help") || value.equals("-h"));
    }

    private static void usage(PrintStream out) {
        out.println("Usage:");
        out.println("  cosbench-config-migrate --source LEGACY_INI --destination VERSIONED_YML [--report REPORT_JSON]");
        out.println("      [--service-token-env NAME] [--driver-key-store PATH] [--controller-trust-store PATH]");
        out.println("  cosbench-config-migrate LEGACY_INI VERSIONED_YML REPORT_JSON");
        out.println("  operator-cli inspect --config VERSIONED_YML [--role controller|driver] [--check-port]");
        out.println("  operator-cli expand-drivers --config DRIVER_YML --output-dir CONF_DIR --count N");
        out.println("  operator-cli bind-controller --config CONTROLLER_YML --drivers-dir CONF_DIR --count N --output OUTPUT_YML");
        out.println("Exit codes: 0 success, 1 configuration or operation failure, 2 usage error.");
    }

    private record MigrationOptions(Path source, Path destination, Path report, MigrationSecuritySettings security) {
    }

    private record Arguments(Map<String, String> values, List<String> flags) {
        static Arguments parse(String[] args, int start, String... allowed) {
            List<String> keys = List.of(allowed);
            Map<String, String> values = new java.util.LinkedHashMap<>();
            List<String> flags = new ArrayList<>();
            for (int index = start; index < args.length; index++) {
                String argument = args[index];
                if (argument.equals("--check-port") && keys.contains(argument)) {
                    flags.add(argument);
                    continue;
                }
                int equals = argument.indexOf('=');
                String key = equals < 0 ? argument : argument.substring(0, equals);
                if (!keys.contains(key)) {
                    throw new UsageException("unknown option: " + argument);
                }
                String value;
                if (equals >= 0) {
                    value = argument.substring(equals + 1);
                } else if (++index < args.length) {
                    value = args[index];
                } else {
                    throw new UsageException(key + " requires a value");
                }
                if (value.isBlank()) {
                    throw new UsageException(key + " must not be blank");
                }
                if (values.putIfAbsent(key, value) != null) {
                    throw new UsageException(key + " was specified more than once");
                }
            }
            return new Arguments(Map.copyOf(values), List.copyOf(flags));
        }
    }

    private static final class UsageException extends IllegalArgumentException {
        private UsageException(String message) {
            super(message);
        }
    }
}
