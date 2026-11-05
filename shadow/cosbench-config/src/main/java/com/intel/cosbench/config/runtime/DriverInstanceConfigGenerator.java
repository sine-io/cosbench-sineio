package com.intel.cosbench.config.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Creates non-destructive, independently configured Driver YAML files from one validated template. */
public final class DriverInstanceConfigGenerator {

    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?)([0-9]+)$");

    private DriverInstanceConfigGenerator() {
    }

    public static List<Path> generate(Path source, Path outputDirectory, int count, SecretResolver resolver) {
        if (count < 1) {
            throw new IllegalArgumentException("driver instance count must be at least 1");
        }
        if (source == null || outputDirectory == null) {
            throw new IllegalArgumentException("source configuration and output directory are required");
        }

        ServiceConfig base = VersionedConfigLoader.load(source,
                resolver == null ? SecretResolver.environment() : resolver);
        if (base.role() != ServiceRole.DRIVER) {
            throw new IllegalArgumentException("Driver instance template must have role: driver");
        }
        long lastPort = (long) base.listen().port() + count - 1;
        if (lastPort > 65535) {
            throw new IllegalArgumentException("driver instance ports exceed 65535");
        }

        Object loaded;
        try {
            loaded = new Yaml().load(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read Driver template " + source, e);
        }
        Map<String, Object> template = objectMap(loaded, "document");
        List<Path> destinations = new ArrayList<>(count);
        List<byte[]> contents = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String instanceName = instanceName(base.name(), index);
            Map<String, Object> config = deepCopy(template);
            config.put("name", instanceName);
            Map<String, Object> listen = nestedMap(config, "listen");
            listen.put("port", base.listen().port() + index - 1);
            Map<String, Object> paths = nestedMap(config, "paths");
            String mission = textValue(paths.get("mission"), "log/mission");
            String logFile = textValue(nestedMap(config, "logging").get("file"), "log/system.log");
            String instanceLog = instanceLogPath(logFile, instanceName);
            paths.put("mission", Path.of(mission).resolve(instanceName).toString());
            paths.put("logs", instanceLog);
            nestedMap(config, "logging").put("file", instanceLog);

            destinations.add(outputDirectory.resolve("driver-" + index + ".yml"));
            contents.add(serialize(config).getBytes(StandardCharsets.UTF_8));
        }

        for (int index = 0; index < destinations.size(); index++) {
            Path destination = destinations.get(index);
            if (Files.isSymbolicLink(destination)) {
                throw new IllegalStateException("refusing to replace symbolic link: " + destination);
            }
            if (Files.exists(destination)) {
                try {
                    if (!java.util.Arrays.equals(Files.readAllBytes(destination), contents.get(index))) {
                        throw new IllegalStateException("refusing to replace existing Driver config: " + destination);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("cannot read existing Driver config: " + destination, e);
                }
            }
        }

        List<Path> created = new ArrayList<>();
        try {
            Files.createDirectories(outputDirectory);
            for (int index = 0; index < destinations.size(); index++) {
                Path destination = destinations.get(index);
                if (Files.exists(destination)) {
                    continue;
                }
                Files.write(destination, contents.get(index), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                created.add(destination);
            }
        } catch (IOException | RuntimeException e) {
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Preserve the original failure; an incomplete owned file is reported on the next run.
                }
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("cannot write Driver instance configurations", e);
        }
        return List.copyOf(destinations);
    }

    private static String instanceName(String baseName, int index) {
        Matcher matcher = TRAILING_NUMBER.matcher(baseName);
        return matcher.matches() && !matcher.group(1).isEmpty()
                ? matcher.group(1) + index
                : baseName + "-" + index;
    }

    private static String instanceLogPath(String value, String instanceName) {
        Path log = Path.of(value);
        Path parent = log.getParent();
        Path instanceParent = parent == null ? Path.of(instanceName) : parent.resolve(instanceName);
        return instanceParent.resolve(log.getFileName()).toString();
    }

    private static String textValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        return objectMap(parent.computeIfAbsent(key, ignored -> new LinkedHashMap<>()), key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value, String location) {
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

    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, copyValue(value)));
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("configuration mapping keys must be strings");
                }
                copy.put(key, copyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DriverInstanceConfigGenerator::copyValue).toList();
        }
        return value;
    }

    private static String serialize(Map<String, Object> config) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        options.setIndent(2);
        return new Yaml(options).dump(config);
    }
}
