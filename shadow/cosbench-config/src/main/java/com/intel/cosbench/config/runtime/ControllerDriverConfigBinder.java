package com.intel.cosbench.config.runtime;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Builds a Controller config from independently validated Driver instance configs. */
public final class ControllerDriverConfigBinder {

    private ControllerDriverConfigBinder() {
    }

    public static Path bind(Path controllerTemplate, Path driverDirectory, int count, Path destination,
            SecretResolver resolver) {
        if (count < 1) {
            throw new IllegalArgumentException("driver instance count must be at least 1");
        }
        if (controllerTemplate == null || driverDirectory == null || destination == null) {
            throw new IllegalArgumentException("Controller template, Driver directory, and destination are required");
        }
        if (samePath(controllerTemplate, destination)) {
            throw new IllegalArgumentException("Controller destination must not replace its template");
        }
        SecretResolver secrets = resolver == null ? SecretResolver.environment() : resolver;
        ServiceConfig controller = VersionedConfigLoader.load(controllerTemplate, secrets);
        if (controller.role() != ServiceRole.CONTROLLER) {
            throw new IllegalArgumentException("Controller template must have role: controller");
        }

        List<Map<String, Object>> driverEntries = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            Path driverPath = driverDirectory.resolve("driver-" + index + ".yml");
            ServiceConfig driver = VersionedConfigLoader.load(driverPath, secrets);
            if (driver.role() != ServiceRole.DRIVER) {
                throw new IllegalArgumentException("Driver instance config must have role: driver: " + driverPath);
            }
            String scheme = driver.auth().tls().enabled() ? "https" : "http";
            String url = URI.create(scheme + "://" + driver.listen().host() + ":" + driver.listen().port()
                    + driver.listen().contextPath()).toString();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", driver.name());
            entry.put("url", url);
            driverEntries.add(entry);
        }

        Object document;
        try {
            document = new Yaml().load(Files.readString(controllerTemplate, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read Controller template " + controllerTemplate, e);
        }
        Map<String, Object> root = map(document, "document");
        root.put("drivers", driverEntries);
        byte[] bytes = serialize(root).getBytes(StandardCharsets.UTF_8);
        if (Files.isSymbolicLink(destination)) {
            throw new IllegalStateException("refusing to replace symbolic link: " + destination);
        }
        if (Files.exists(destination)) {
            try {
                if (java.util.Arrays.equals(Files.readAllBytes(destination), bytes)) {
                    return destination;
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read existing Controller config: " + destination, e);
            }
            throw new IllegalStateException("refusing to replace existing Controller config: " + destination);
        }

        try {
            if (destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write Controller config: " + destination, e);
        }
        return destination;
    }

    private static boolean samePath(Path left, Path right) {
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (normalizedLeft.equals(normalizedRight)) {
            return true;
        }
        try {
            return Files.exists(normalizedLeft) && Files.exists(normalizedRight)
                    && Files.isSameFile(normalizedLeft, normalizedRight);
        } catch (IOException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String location) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(location + " must be a mapping");
        }
        for (Object key : raw.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(location + " keys must be strings");
            }
        }
        return (Map<String, Object>) raw;
    }

    private static String serialize(Map<String, Object> config) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        options.setIndent(2);
        return new Yaml(options).dump(config);
    }
}
