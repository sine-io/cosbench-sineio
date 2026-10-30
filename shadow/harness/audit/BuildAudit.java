import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Dependency admission uses reviewed coordinates, SHA-256 values and SPDX licenses. */
public final class BuildAudit {
    private static final Set<String> LICENSES = Set.of("Apache-2.0", "MIT", "MIT-0", "BSD-2-Clause", "BSD-3-Clause",
            "EPL-1.0", "EPL-2.0", "CC0-1.0",
            "LicenseRef-Castor-1.3.3 AND Apache-2.0",
            "CDDL-1.0 OR GPL-2.0-only WITH Classpath-exception-2.0",
            "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0");

    record Dependency(String coordinate, String scope, Path file) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("dependencies")) {
            checkDependencies(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
        } else if (args.length == 2 && args[0].equals("modules")) {
            modules(Path.of(args[1]));
        } else if (args.length >= 3 && args[0].equals("lock-candidate")) {
            lockCandidate(Path.of(args[1]), java.util.Arrays.copyOfRange(args, 2, args.length));
        } else {
            throw new IllegalArgumentException("Usage: BuildAudit dependencies LIST LOCK REACTOR OUTPUT_DIR | modules EFFECTIVE_POM | lock-candidate MAVEN_REPO LIST...");
        }
    }

    private static Element xml(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        if (parent != null) {
            for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && element.getTagName().equals(name)) result.add(element);
            }
        }
        return result;
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static String value(Element parent, String name) {
        var element = child(parent, name);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static void modules(Path pom) throws Exception {
        Element root = xml(pom);
        List<Element> projects = root.getTagName().equals("projects") ? children(root, "project") : List.of(root);
        var pinnedPlugins = new LinkedHashMap<String, String>();
        for (Element plugin : children(child(child(projects.getFirst(), "build"), "plugins"), "plugin")) {
            pinnedPlugins.put(value(plugin, "artifactId"), value(plugin, "version"));
        }
        int count = 0;
        for (Element project : projects) {
            String artifact = value(project, "artifactId");
            if (!value(child(project, "properties"), "maven.compiler.release").equals("21")) {
                throw new IllegalStateException("Expected release=21 in " + artifact);
            }
            for (String skip : List.of("maven.test.skip", "skipTests", "enforcer.skip")) {
                if (value(child(project, "properties"), skip).equalsIgnoreCase("true")) {
                    throw new IllegalStateException("Disabled verification in " + artifact + ": " + skip);
                }
            }
            var plugins = children(child(child(project, "build"), "plugins"), "plugin");
            for (var pin : pinnedPlugins.entrySet()) {
                Element plugin = plugins.stream().filter(item -> value(item, "artifactId").equals(pin.getKey()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Missing pinned plugin in " + artifact));
                if (!value(plugin, "version").equals(pin.getValue())) {
                    throw new IllegalStateException("Overridden plugin version in " + artifact + ": " + pin.getKey());
                }
                Element configuration = child(plugin, "configuration");
                verificationEnabled(configuration, artifact, pin.getKey(), "plugin");
                for (Element execution : children(child(plugin, "executions"), "execution")) {
                    verificationEnabled(child(execution, "configuration"), artifact, pin.getKey(), "verification execution");
                }
                if (pin.getKey().equals("maven-compiler-plugin") && !value(configuration, "release").equals("21")) {
                    throw new IllegalStateException("Overridden compiler release in " + artifact);
                }
                if (pin.getKey().equals("maven-enforcer-plugin")) {
                    boolean convergence = children(child(plugin, "executions"), "execution").stream()
                            .filter(execution -> value(execution, "phase").equals("validate"))
                            .filter(execution -> children(child(execution, "goals"), "goal").stream()
                                    .anyMatch(goal -> goal.getTextContent().trim().equals("enforce")))
                            .map(execution -> child(child(execution, "configuration"), "rules"))
                            .anyMatch(rules -> List.of("requireJavaVersion", "requireMavenVersion", "requireProperty", "dependencyConvergence")
                                    .stream().allMatch(rule -> child(rules, rule) != null));
                    if (!convergence) throw new IllegalStateException("Missing convergence policy in " + artifact);
                }
            }
            if (value(project, "packaging").equals("pom")) continue;
            if (!Set.of("", "jar").contains(value(project, "packaging"))) {
                throw new IllegalStateException("Unaudited packaging in " + artifact);
            }
            Element build = child(project, "build");
            String buildText = build.getTextContent().replace('\\', '/');
            if (buildText.matches("(?s).*(legacy/osgi|dist/osgi|build/p2|org.eclipse.tycho|spring-osgi).*")) {
                throw new IllegalStateException("Forbidden build input in " + artifact);
            }
            String directory = value(build, "directory");
            String jar = Path.of(directory, value(build, "finalName") + ".jar").toString();
            String coordinate = value(project, "groupId") + ":" + artifact + ":jar:" + value(project, "version");
            System.out.println(String.join("\t", coordinate, jar, directory,
                    value(build, "outputDirectory"), value(build, "testOutputDirectory"), artifact));
            count++;
        }
        if (count == 0) throw new IllegalStateException("No active JAR modules were audited");
    }

    private static void verificationEnabled(Element configuration, String artifact, String plugin, String boundary) {
        for (String skip : List.of("skip", "skipTests", "skipMain")) {
            if (value(configuration, skip).equalsIgnoreCase("true")) {
                throw new IllegalStateException("Disabled " + boundary + " in " + artifact + ": " + plugin);
            }
        }
        if (plugin.equals("maven-enforcer-plugin") && value(configuration, "fail").equalsIgnoreCase("false")) {
            throw new IllegalStateException("Nonblocking Enforcer execution in " + artifact);
        }
    }

    // This command only proposes admission records. CI never writes or refreshes the committed lock.
    private static void lockCandidate(Path repository, String[] lists) throws Exception {
        Map<String, Dependency> all = new java.util.TreeMap<>();
        for (String list : lists) {
            for (Dependency dependency : dependencies(Path.of(list))) {
                if (!dependency.coordinate().startsWith("com.intel.cosbench.shadow:")) {
                    all.put(dependency.coordinate(), dependency);
                }
            }
        }
        for (Dependency dependency : all.values()) {
            String[] parts = dependency.coordinate().split(":");
            String version = parts[parts.length - 1];
            Path pom = repository.resolve(parts[0].replace('.', '/')).resolve(parts[1]).resolve(version)
                    .resolve(parts[1] + "-" + version + ".pom");
            String metadata = licenseMetadata(pom, repository);
            System.err.println(dependency.coordinate() + "\t" + metadata);
            String license = licenseId(metadata);
            System.out.println(dependency.coordinate() + "\t" + sha256(dependency.file()) + "\t" + license);
        }
    }

    private static String licenseMetadata(Path pom, Path repository) throws Exception {
        Element root = xml(pom);
        var licenses = children(child(root, "licenses"), "license");
        if (!licenses.isEmpty()) {
            return licenses.stream().map(license -> value(license, "name") + " " + value(license, "url"))
                    .collect(java.util.stream.Collectors.joining(" | "));
        }
        Element parent = child(root, "parent");
        if (parent == null) return "MISSING license metadata: " + pom;
        return licenseMetadata(repository.resolve(value(parent, "groupId").replace('.', '/'))
                .resolve(value(parent, "artifactId")).resolve(value(parent, "version"))
                .resolve(value(parent, "artifactId") + "-" + value(parent, "version") + ".pom"), repository);
    }

    private static String licenseId(String metadata) {
        String lower = metadata.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("apache")) return "Apache-2.0";
        if (lower.contains("edl") || lower.contains("eclipse distribution")) return "BSD-3-Clause";
        if (lower.contains("mit-0")) return "MIT-0";
        if (lower.contains("eclipse") && lower.contains("2.0")) {
            boolean gplWithClasspath = lower.matches("(?s).*\\bgpl(?:[- /]?2|v2).*")
                    && (lower.contains("classpath") || lower.contains("cpe"));
            return gplWithClasspath ? "EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0" : "EPL-2.0";
        }
        if (lower.contains("eclipse")) return "EPL-1.0";
        if (lower.contains("cddl") && lower.contains("gpl")) return "CDDL-1.0 OR GPL-2.0-only WITH Classpath-exception-2.0";
        if (lower.contains("cc0") || lower.contains("public domain")) return "CC0-1.0";
        if (lower.contains("mit")) return "MIT";
        if (lower.contains("bsd-2") || lower.contains("2-clause")) return "BSD-2-Clause";
        if (lower.contains("bsd")) return "BSD-3-Clause";
        return "REVIEW: " + metadata;
    }

    static List<Dependency> dependencies(Path list) throws Exception {
        var result = new ArrayList<Dependency>();
        for (String line : Files.readAllLines(list)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("The following") || line.equals("none")) continue;
            String[] parts = line.split(" -- module ", 2)[0].trim().split(":");
            if (parts.length < 6 || parts.length > 7 || !parts[2].equals("jar")) {
                throw new IllegalStateException("Unsupported dependency record: " + line);
            }
            result.add(new Dependency(String.join(":", java.util.Arrays.copyOf(parts, parts.length - 2)),
                    parts[parts.length - 2], Path.of(parts[parts.length - 1])));
        }
        return result;
    }

    static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void checkDependencies(Path list, Path lock, Path reactor, Path output) throws Exception {
        Map<String, String[]> admitted = new LinkedHashMap<>();
        for (String line : Files.readAllLines(lock)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t");
            if (fields.length != 3 || !fields[1].matches("[0-9a-f]{64}")
                    || admitted.put(fields[0], fields) != null) {
                throw new IllegalStateException("Invalid admission record: " + line);
            }
            if (!LICENSES.contains(fields[2])) throw new IllegalStateException("Unapproved license: " + line);
        }
        Map<String, Path> modules = new LinkedHashMap<>();
        for (String line : Files.readAllLines(reactor)) {
            String[] fields = line.split("\t");
            modules.put(fields[0], Path.of(fields[1]));
        }
        Files.createDirectories(output);
        Files.createDirectories(output.resolve("test"));
        var classpath = new ArrayList<String>();
        var report = new ArrayList<String>();
        for (Dependency dependency : dependencies(list)) {
            String coordinate = dependency.coordinate();
            String lower = coordinate.toLowerCase(java.util.Locale.ROOT);
            boolean test = dependency.scope().equals("test");
            if (!Set.of("compile", "runtime", "test").contains(dependency.scope())) {
                throw new IllegalStateException("Unsupported dependency scope: " + dependency);
            }
            if (lower.matches(".*(org\\.osgi|org\\.eclipse\\.(osgi|equinox|tycho)|springframework\\.osgi|spring-osgi|spring-dm|:cosbench-mock|:cosbench-tomcat|:p2-|:legacy).*")) {
                throw new IllegalStateException("Forbidden dependency: " + coordinate);
            }
            if (!test && (lower.startsWith("javax.servlet:") || lower.contains(":tomcat") && !lower.contains(":10.1."))) {
                throw new IllegalStateException("Legacy Servlet/Tomcat dependency: " + coordinate);
            }
            if (!test && lower.matches(".*(org\\.junit|junit:junit|org\\.mockito|org\\.assertj|org\\.hamcrest|org\\.opentest4j|org\\.apiguardian|spring-boot-starter-test|spring-test).*")) {
                throw new IllegalStateException("Test dependency in production scope: " + coordinate);
            }
            String checksum = sha256(dependency.file());
            String license;
            if (modules.containsKey(coordinate)) {
                if (!checksum.equals(sha256(modules.get(coordinate)))) {
                    throw new IllegalStateException("Reactor artifact mismatch: " + coordinate);
                }
                license = "Apache-2.0 (reactor)";
            } else {
                String[] record = admitted.get(coordinate);
                if (record == null) throw new IllegalStateException("Dependency is not admitted: " + coordinate);
                if (!checksum.equals(record[1])) throw new IllegalStateException("Checksum mismatch: " + coordinate);
                license = record[2];
            }
            report.add(coordinate + "\t" + dependency.scope() + "\t" + checksum + "\t" + license);
            Files.copy(dependency.file(), output.resolve("test").resolve(coordinate.replace(':', '_') + ".jar"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!test) {
                classpath.add(dependency.file().toString());
                Files.copy(dependency.file(), output.resolve(coordinate.replace(':', '_') + ".jar"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(output.resolve("classpath.txt"), String.join(":", classpath));
        report.forEach(System.out::println);
    }
}
