package com.intel.cosbench.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.intel.cosbench.config.runtime.ServicePaths;

/** Read-only view over the existing archive path; migration never rewrites these files. */
public final class ArchiveReader {

    private final Path baseDirectory;
    private final ServicePaths paths;

    public ArchiveReader(Path baseDirectory, ServicePaths paths) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.paths = paths;
    }

    public Path archiveDirectory() {
        return paths.archiveAt(baseDirectory);
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public ArchiveSnapshot snapshot() {
        Path archive = archiveDirectory();
        if (!Files.isDirectory(archive)) {
            return new ArchiveSnapshot(archive.toString(), false, List.of());
        }
        try (var stream = Files.walk(archive)) {
            List<ArchiveEntry> entries = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString))
                    .map(this::entry)
                    .toList();
            return new ArchiveSnapshot(archive.toString(), true, entries);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read archive directory " + archive, e);
        }
    }

    private ArchiveEntry entry(Path path) {
        try {
            String relativePath;
            try {
                relativePath = baseDirectory.relativize(path).toString();
            } catch (IllegalArgumentException ignored) {
                relativePath = path.toString();
            }
            return new ArchiveEntry(relativePath, Files.size(path), sha256(Files.readAllBytes(path)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot inspect archive file " + path, e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record ArchiveSnapshot(String path, boolean exists, List<ArchiveEntry> entries) {
        public ArchiveSnapshot {
            entries = List.copyOf(entries == null ? List.of() : entries);
        }
    }

    public record ArchiveEntry(String path, long size, String sha256) {
    }
}
