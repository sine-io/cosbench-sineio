import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Checks the effective Java 21 classpath, including multi-release JAR entries. */
public final class DuplicateClasses {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Files.isDirectory(Path.of(args[1]))) {
            throw new IllegalArgumentException("Usage: DuplicateClasses ARTIFACT DEPENDENCY_DIR");
        }
        var jars = new ArrayList<Path>();
        jars.add(Path.of(args[0]));
        try (var files = Files.list(Path.of(args[1]))) {
            jars.addAll(files.filter(path -> path.toString().endsWith(".jar")).sorted().toList());
        }
        Map<String, String> owners = new TreeMap<>();
        var duplicates = new ArrayList<String>();
        for (Path file : jars) {
            try (var jar = new JarFile(file.toFile(), true, ZipFile.OPEN_READ, Runtime.Version.parse("21"))) {
                for (var entry : jar.versionedStream().toList()) {
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.equals("module-info.class") || name.startsWith("META-INF/")) continue;
                    String previous = owners.putIfAbsent(name, file.toString());
                    if (previous != null) duplicates.add(name + "\n  " + previous + "\n  " + file);
                }
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("Duplicate classes:\n" + String.join("\n", duplicates));
        }
        System.out.println("No duplicate classes on the Java 21 classpath (" + jars.size() + " JARs).");
    }
}
