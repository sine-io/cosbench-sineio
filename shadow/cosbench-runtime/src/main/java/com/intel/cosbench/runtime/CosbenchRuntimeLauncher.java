package com.intel.cosbench.runtime;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.intel.cosbench.config.runtime.LegacyConfigMigrator;
import com.intel.cosbench.config.runtime.MigrationReport;
import com.intel.cosbench.config.runtime.ServiceRole;

/** Entry point for the two process boundaries and the migration command in the spike. */
@SpringBootApplication
public class CosbenchRuntimeLauncher {

    public static void main(String[] args) {
        if (contains(args, "--migrate")) {
            int status = migrate(args);
            if (status != 0) {
                System.exit(status);
            }
            return;
        }
        ServiceRole role = role(args);
        System.setProperty("cosbench.role", role.wireName());
        String configPath = configPath(args, role);
        System.setProperty("cosbench.config", configPath);
        var loaded = com.intel.cosbench.config.runtime.VersionedConfigLoader.load(Path.of(configPath), null);
        System.setProperty("spring.lifecycle.timeout-per-shutdown-phase",
                loaded.shutdown().gracePeriod().toMillis() + "ms");
        System.setProperty("server.shutdown", "graceful");
        SpringApplication.run(CosbenchRuntimeLauncher.class, normalizedArgs(args, configPath));
    }

    private static int migrate(String[] args) {
        String[] values = after(args, "--migrate");
        if (values.length != 3) {
            System.err.println("usage: --migrate SOURCE_INI DESTINATION_YAML REPORT_JSON");
            return 2;
        }
        MigrationReport report = LegacyConfigMigrator.convert(Path.of(values[0]), Path.of(values[1]), Path.of(values[2]));
        System.out.println(report);
        return report.success() ? 0 : 1;
    }

    private static ServiceRole role(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--role=")) {
                return ServiceRole.parse(arg.substring("--role=".length()));
            }
        }
        String property = System.getProperty("cosbench.role", "controller");
        return ServiceRole.parse(property);
    }

    private static String configPath(String[] args, ServiceRole role) {
        for (String arg : args) {
            if (arg.startsWith("--cosbench.config=")) {
                return arg.substring("--cosbench.config=".length());
            }
        }
        String environment = System.getenv("COSBENCH_CONFIG");
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        return Path.of("conf", role.wireName() + ".yml").toString();
    }

    private static String[] normalizedArgs(String[] args, String configPath) {
        List<String> normalized = new ArrayList<>();
        boolean hasConfig = false;
        for (String arg : args) {
            if (arg.startsWith("--cosbench.config=")) {
                hasConfig = true;
            }
            normalized.add(arg);
        }
        if (!hasConfig) {
            normalized.add("--cosbench.config=" + configPath);
        }
        return normalized.toArray(String[]::new);
    }

    private static boolean contains(String[] args, String value) {
        return Arrays.asList(args).contains(value);
    }

    private static String[] after(String[] args, String value) {
        int index = Arrays.asList(args).indexOf(value);
        return index < 0 ? new String[0] : Arrays.copyOfRange(args, index + 1, args.length);
    }
}
