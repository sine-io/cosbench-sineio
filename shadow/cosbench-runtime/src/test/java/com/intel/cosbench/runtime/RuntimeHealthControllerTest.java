package com.intel.cosbench.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

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

class RuntimeHealthControllerTest {

    @Test
    void reportsStartingUntilApplicationReadyThenUp() {
        ServiceConfig config = new ServiceConfig(1, ServiceRole.DRIVER, "driver1",
                new ListenConfig("127.0.0.1", 18088, "/driver"),
                new ServicePaths(Path.of("workspace"), Path.of("archive"), Path.of("mission"), Path.of("log/system.log")),
                List.of(), new AuthConfig(null, new TlsConfig(false, null, null)),
                new HttpConfig(new HttpPoolConfig(4, 2, Duration.ofSeconds(5)),
                        new HttpTimeoutConfig(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4)),
                        new RetryConfig(1, Duration.ZERO)),
                1, new ProviderConfig("mock", 1), new LoggingConfig("INFO", Path.of("log/system.log")),
                new WebConfig(true, 1024), new ShutdownConfig(Duration.ofSeconds(1)));
        ServiceRuntime runtime = new ServiceRuntime(config, new ArchiveReader(Path.of("."), config.paths()));
        RuntimeHealthController controller = new RuntimeHealthController(runtime, runtime.archiveReader());
        assertEquals("STARTING", controller.health().getBody().get("status"));
        runtime.markReady();
        assertEquals("UP", controller.health().getBody().get("status"));
        runtime.stop();
    }
}
