package com.intel.cosbench.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.intel.cosbench.config.runtime.AuthConfig;
import com.intel.cosbench.config.runtime.HttpConfig;
import com.intel.cosbench.config.runtime.HttpPoolConfig;
import com.intel.cosbench.config.runtime.HttpTimeoutConfig;
import com.intel.cosbench.config.runtime.ListenConfig;
import com.intel.cosbench.config.runtime.LoggingConfig;
import com.intel.cosbench.config.runtime.ProviderConfig;
import com.intel.cosbench.config.runtime.RetryConfig;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.ServicePaths;
import com.intel.cosbench.config.runtime.ServiceRole;
import com.intel.cosbench.config.runtime.ShutdownConfig;
import com.intel.cosbench.config.runtime.TlsConfig;
import com.intel.cosbench.config.runtime.WebConfig;

class ServiceRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void readsArchiveAndRejectsNewTasksAfterGracefulStop() throws Exception {
        Path archive = tempDir.resolve("archive/legacy");
        Files.createDirectories(archive);
        Files.writeString(archive.resolve("workload.xml"), "<workload name=\"legacy\" />");
        ServiceConfig config = new ServiceConfig(1, ServiceRole.CONTROLLER, "Controller",
                new ListenConfig("127.0.0.1", 19088, "/controller"),
                new ServicePaths(Path.of("workspace"), Path.of("archive"), Path.of("mission"), Path.of("log/system.log")),
                List.of(), new AuthConfig(null, new TlsConfig(false, null, null)),
                new HttpConfig(new HttpPoolConfig(4, 2, Duration.ofSeconds(5)),
                        new HttpTimeoutConfig(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4)),
                        new RetryConfig(1, Duration.ZERO)),
                1, new ProviderConfig("mock", 1), new LoggingConfig("INFO", Path.of("log/system.log")),
                new WebConfig(true, 1024), new ShutdownConfig(Duration.ofSeconds(1)));
        ArchiveReader reader = new ArchiveReader(tempDir, config.paths());
        ServiceRuntime runtime = new ServiceRuntime(config, reader);

        assertTrue(reader.snapshot().exists());
        assertEquals(1, reader.snapshot().entries().size());
        assertEquals(ServiceRuntime.TaskAdmission.ACCEPTED, runtime.submit(() -> { }));
        runtime.stop();
        assertFalse(runtime.isAccepting());
        assertEquals(ServiceRuntime.TaskAdmission.REJECTED, runtime.submit(() -> { }));
    }
}
