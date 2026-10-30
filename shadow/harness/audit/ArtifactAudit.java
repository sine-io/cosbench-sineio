import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Audits the published JAR, including resources staged by Maven. */
public final class ArtifactAudit {
    private enum OutputScope { PRODUCTION, TEST, MOCK_TEST }
    private enum ArchiveOrigin { APPLICATION, DEPENDENCY }

    private static final List<String> FORBIDDEN = List.of(
            "org/osgi/", "org/eclipse/osgi/", "org/eclipse/equinox/", "org/springframework/osgi/",
            "springframework/org/schema/osgi",
            "javax/servlet/", "OSGI-INF/", "legacy/osgi/", "org/eclipse/tycho/", "org/eclipse/equinox/p2/",
            "com/intel/cosbench/api/amplistor/", "com/intel/cosbench/api/cdmi/",
            "com/intel/cosbench/api/cdmiswift/", "com/intel/cosbench/api/GcsStor/",
            "com/intel/cosbench/api/librados/", "com/intel/cosbench/api/openio/",
            "com/intel/cosbench/api/oss/", "com/intel/cosbench/api/swift/",
            "com/intel/cosbench/api/keystone/", "com/intel/cosbench/api/httpauth/",
            "com/intel/cosbench/api/swauth/", "com/emc/vipr/cosbench/", "com/joyent/manta/cosbench/",
            "com/scality/cosbench/");
    private static final List<String> TEST_LIBRARIES = List.of(
            "org/junit/", "org/mockito/", "org/assertj/", "org/hamcrest/", "org/opentest4j/",
            "org/apiguardian/", "org/springframework/test/", "org/springframework/boot/test/");

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Usage: ArtifactAudit production|dependency|test|mock-test JAR [STAGED_RESOURCES [DEPENDENCY_DIR]]");
        }
        if (!List.of("production", "dependency", "test", "mock-test").contains(args[0])) {
            throw new IllegalArgumentException("Unknown artifact scope: " + args[0]);
        }
        OutputScope scope = args[0].equals("mock-test") ? OutputScope.MOCK_TEST
                : args[0].equals("test") ? OutputScope.TEST : OutputScope.PRODUCTION;
        ArchiveOrigin origin = args[0].equals("dependency") ? ArchiveOrigin.DEPENDENCY : ArchiveOrigin.APPLICATION;
        Set<String> expectedDependencies = null;
        if (args.length == 4) {
            expectedDependencies = new HashSet<>();
            try (var files = Files.list(Path.of(args[3]))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                    expectedDependencies.add(hash(Files.readAllBytes(file)));
                }
            }
        }
        scan(Files.readAllBytes(Path.of(args[1])), args[1], scope, origin, expectedDependencies);
        try (var jar = new ZipFile(args[1])) {
            if (args.length >= 3) {
                Path resources = Path.of(args[2]);
                if (!Files.isDirectory(resources)) {
                    throw new IllegalStateException("Missing staged resources directory: " + resources);
                }
                try (var files = Files.walk(resources)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String name = resources.relativize(file).toString().replace('\\', '/');
                        var entry = jar.getEntry(name);
                        if (entry == null) entry = jar.getEntry("BOOT-INF/classes/" + name);
                        if (entry == null) {
                            throw new IllegalStateException("Missing packaged resource: " + name + " in " + args[1]);
                        }
                        if (!Arrays.equals(Files.readAllBytes(file), jar.getInputStream(entry).readAllBytes())) {
                            throw new IllegalStateException("Packaged resource differs: " + name + " in " + args[1]);
                        }
                    }
                }
            }
        }
        System.out.println("PASS artifact " + args[1]);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void scan(byte[] archive, String location, OutputScope scope, ArchiveOrigin origin,
                             Set<String> expectedDependencies) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                byte[] bytes = zip.readAllBytes();
                String content = new String(bytes, StandardCharsets.ISO_8859_1).replace('.', '/');
                String path = location + "!/" + name;
                if (name.endsWith(".jar")) {
                    if (expectedDependencies != null && !expectedDependencies.contains(hash(bytes))) {
                        throw new IllegalStateException("Unlisted packaged dependency: " + path);
                    }
                    ArchiveOrigin nestedOrigin = name.substring(name.lastIndexOf('/') + 1).startsWith("cosbench-")
                            ? ArchiveOrigin.APPLICATION : ArchiveOrigin.DEPENDENCY;
                    scan(bytes, path, scope, nestedOrigin, expectedDependencies);
                    continue;
                }
                boolean classOrDescriptor = name.endsWith(".class") || name.contains("META-INF/services/")
                        || (name.endsWith(".xml") && !name.startsWith("META-INF/maven/")) || name.endsWith(".properties");
                if (origin == ArchiveOrigin.APPLICATION && name.equals("META-INF/MANIFEST.MF") && content.contains("Bundle-SymbolicName:")) {
                    throw new IllegalStateException("Forbidden OSGi application manifest: " + path);
                }
                for (String forbidden : FORBIDDEN) {
                    if (origin == ArchiveOrigin.DEPENDENCY && forbidden.equals("OSGI-INF/")) continue;
                    // Optional APIs inside third-party libraries do not establish an application dependency.
                    // Their definitions are still forbidden; jdeps checks every migrated class's actual references.
                    if (name.contains(forbidden) || (origin == ArchiveOrigin.APPLICATION && classOrDescriptor && content.contains(forbidden))) {
                        throw new IllegalStateException("Forbidden artifact reference: " + forbidden + " in " + path);
                    }
                }
                if (scope != OutputScope.MOCK_TEST) {
                    if (name.contains("com/intel/cosbench/api/mock/")
                            || (origin == ArchiveOrigin.APPLICATION && classOrDescriptor && content.contains("com/intel/cosbench/api/mock/"))) {
                        throw new IllegalStateException("Mock leaked into production: " + path);
                    }
                }
                if (scope == OutputScope.PRODUCTION) {
                    for (String testLibrary : TEST_LIBRARIES) {
                        if (name.startsWith(testLibrary) || (origin == ArchiveOrigin.APPLICATION && name.endsWith(".class") && content.contains(testLibrary))) {
                            throw new IllegalStateException("Test dependency leaked into production: " + path);
                        }
                    }
                }
                if (name.endsWith(".class") && name.contains("com/intel/cosbench/")
                        && (bytes.length < 8 || (((bytes[6] & 255) << 8) | (bytes[7] & 255)) != 65)) {
                    throw new IllegalStateException("Expected release=21 class major 65: " + path);
                }
            }
        }
    }
}
