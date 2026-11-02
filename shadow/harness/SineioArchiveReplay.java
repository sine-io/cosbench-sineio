import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.api.storage.StorageAPI;
import com.intel.cosbench.api.storage.StorageException;
import com.intel.cosbench.config.Config;
import com.intel.cosbench.config.common.KVConfigParser;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.LogLevel;

/** Strict backend acceptance: initially readable STANDARD objects cannot pass. */
public final class SineioArchiveReplay {
    private static final List<String> MODES = List.of("sio", "siov1", "gdas");

    public static void main(String[] args) {
        try {
            replay(Path.of(args[0]), Path.of(args[1]));
        } catch (Exception error) {
            // Do not emit SDK exceptions, which can contain request or credential details.
            System.err.println("Archive acceptance FAILED: " + error.getClass().getSimpleName()
                    + (error instanceof FixtureException ? ": " + error.getMessage() : ""));
            System.exit(1);
        }
    }

    private static void replay(Path fixture, Path reports) throws Exception {
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("summary.txt"), "status=failed\n");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(fixture, StandardCharsets.UTF_8)) { properties.load(reader); }
        long deadlineSeconds = positive(properties, "restoreDeadlineSeconds");
        long pollSeconds = positive(properties, "pollIntervalSeconds");
        if (pollSeconds > 30) throw new FixtureException("pollIntervalSeconds must be <= 30");
        LogFactory.getSystemLogManager().setLogLevel(LogLevel.ERROR);
        // Preflight all three modes before submitting any restore request.
        var inputs = MODES.stream().map(mode -> Input.load(properties, fixture.getParent(), mode)).toList();
        var registry = StaticProviderRegistry.discover(SineioArchiveReplay.class.getClassLoader(), Set.copyOf(MODES), Set.of());
        if (!registry.storageNames().equals(Set.copyOf(MODES)) || !registry.authNames().isEmpty()) {
            throw new IllegalStateException("Unexpected production providers");
        }
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true)
                .name("sineio-archive-replay").factory());
        try {
            for (Input input : inputs) {
                String mode = input.mode;
                Files.writeString(reports.resolve("summary.txt"), "status=failed\nmode=" + mode + "\n");
                long started = System.nanoTime();
                var task = executor.submit(() -> { exerciseMode(registry, input, pollSeconds); return null; });
                try {
                    // Covers restore, blocked network reads, polling and extension reads.
                    task.get(deadlineSeconds, TimeUnit.SECONDS);
                } catch (ExecutionException error) {
                    if (error.getCause() instanceof Exception cause) throw cause;
                    throw new IllegalStateException("Archive worker failed");
                } finally {
                    task.cancel(true);
                }
                // Only the deadline-owning thread publishes successful results.
                Files.writeString(reports.resolve(mode + ".txt"), "status=passed\ninitial_state=archived\n"
                        + "restore=data_verified\nsha256=" + input.sha256 + "\nbytes=" + input.objectBytes
                        + "\nprefetch_and_range=" + (mode.equals("gdas") ? "not_declared" : "data_verified")
                        + "\nelapsed_seconds=" + Duration.ofNanos(System.nanoTime() - started).toSeconds() + "\n");
            }
        } finally {
            // Do not await an SDK that consumed interruption; main exits nonzero on timeout.
            executor.shutdownNow();
        }
        Files.writeString(reports.resolve("summary.txt"), "status=passed\nmodes=sio,siov1,gdas\nbackend=real_archive\n");
    }

    private static void exerciseMode(StaticProviderRegistry registry, Input input, long pollSeconds) throws Exception {
        StorageAPI storage = registry.createStorage(input.mode, input.config(""), LogFactory.getSystemLogger());
        Fingerprint restored;
        try {
            requireArchived(storage, input);
            storage.restoreObject(input.bucket, input.object, input.config(""));
            while (true) {
                try {
                    restored = verifyData(storage, input, input.objectBytes, input.sha256);
                    break;
                } catch (StorageException pending) {
                    if (!"InvalidObjectState".equals(errorCode(pending))) throw pending;
                    Thread.sleep(Duration.ofSeconds(pollSeconds));
                }
            }
        } finally {
            storage.dispose();
        }
        if (!input.mode.equals("gdas")) {
            verifyExtension(registry, input, ";is_prefetch=true;is_range_request=false", input.objectBytes, input.sha256);
            verifyExtension(registry, input, ";is_prefetch=false;is_range_request=true;file_length="
                    + restored.prefixBytes + ";chunk_length=" + restored.prefixBytes,
                    restored.prefixBytes, restored.prefixSha256);
        }
    }

    private static void requireArchived(StorageAPI storage, Input input) throws Exception {
        try (var readable = storage.getObject(input.bucket, input.object, input.config(""))) {
            throw new FixtureException("Fixture must be archived and unreadable before restore: " + input.mode);
        } catch (StorageException archived) {
            if (!"InvalidObjectState".equals(errorCode(archived))) throw archived;
        }
    }

    private static void verifyExtension(StaticProviderRegistry registry, Input input, String extra,
            long expectedBytes, String expectedSha256) throws Exception {
        var storage = registry.createStorage(input.mode, input.config(extra), LogFactory.getSystemLogger());
        try { verifyData(storage, input, expectedBytes, expectedSha256); }
        finally { storage.dispose(); }
    }

    private static Fingerprint verifyData(StorageAPI storage, Input input, long expectedBytes, String expectedSha256)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        MessageDigest prefixDigest = MessageDigest.getInstance("SHA-256");
        int prefixBytes = (int) Math.min(65536, Math.max(1, expectedBytes / 2));
        long bytes = 0;
        try (InputStream stream = storage.getObject(input.bucket, input.object, input.config(""));
                var hashing = new DigestInputStream(stream, digest)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = hashing.read(buffer)) != -1) {
                if (bytes < prefixBytes) prefixDigest.update(buffer, 0, (int) Math.min(count, prefixBytes - bytes));
                bytes += count;
                if (bytes > expectedBytes) throw new FixtureException("Response exceeds expected object length: " + input.mode);
            }
        }
        if (bytes != expectedBytes || !HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
            throw new IllegalStateException("Restored object does not match fixture length and SHA-256");
        }
        return new Fingerprint(prefixBytes, HexFormat.of().formatHex(prefixDigest.digest()));
    }

    private static String errorCode(StorageException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof software.amazon.awssdk.services.s3.model.S3Exception s3) {
            return s3.awsErrorDetails() == null ? "" : s3.awsErrorDetails().errorCode();
        }
        if (cause instanceof com.amazonaws.services.s3.model.AmazonS3Exception s3) return s3.getErrorCode();
        return "";
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.contains("${")) throw new FixtureException("Missing fixture value: " + key);
        return value.trim();
    }

    private static long positive(Properties properties, String key) {
        long value = Long.parseLong(required(properties, key));
        if (value <= 0) throw new FixtureException("Non-positive fixture value: " + key);
        return value;
    }

    private record Input(String mode, String values, String bucket, String object, String sha256, long objectBytes) {
        static Input load(Properties properties, Path folder, String mode) {
            try {
                String base = required(properties, mode + ".storageConfig");
                var endpoint = java.net.URI.create(KVConfigParser.parse(base).get("endpoint", ""));
                if (endpoint.getHost() == null || !Set.of("http", "https").contains(endpoint.getScheme())) {
                    throw new FixtureException("Explicit HTTP(S) endpoint required: " + mode);
                }
                String access = Files.readString(folder.resolve(required(properties, mode + ".accessKeyFile"))).trim();
                String secret = Files.readString(folder.resolve(required(properties, mode + ".secretKeyFile"))).trim();
                String sha256 = required(properties, mode + ".sha256").toLowerCase(java.util.Locale.ROOT);
                if (!sha256.matches("[0-9a-f]{64}") || access.isBlank() || secret.isBlank()) {
                    throw new FixtureException("Invalid fixture credentials or SHA-256: " + mode);
                }
                long bytes = positive(properties, mode + ".objectBytes");
                if (!mode.equals("gdas") && bytes < 2) throw new FixtureException("Partial range fixture must exceed one byte: " + mode);
                return new Input(mode, base + ";accesskey=" + access + ";secretkey=" + secret,
                        required(properties, mode + ".bucket"), required(properties, mode + ".object"),
                        sha256, bytes);
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }

        Config config(String extra) { return KVConfigParser.parse(values + extra); }
    }

    private record Fingerprint(int prefixBytes, String prefixSha256) { }

    private static final class FixtureException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        FixtureException(String message) { super(message); }
    }
}
