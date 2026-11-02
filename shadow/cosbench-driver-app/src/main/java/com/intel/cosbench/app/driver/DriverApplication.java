package com.intel.cosbench.app.driver;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.intel.cosbench.config.runtime.ConfigLoadException;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.ServiceRole;
import com.intel.cosbench.config.runtime.VersionedConfigLoader;

/** Driver composition root; only the process entry point assigns an exit status. */
@SpringBootApplication
public class DriverApplication {
    public static void main(String[] args) {
        try {
            var arguments = new DefaultApplicationArguments(args);
            var values = arguments.getOptionValues("cosbench.config");
            String selected = values == null ? System.getenv("COSBENCH_CONFIG")
                    : values.isEmpty() ? "" : values.getLast();
            boolean explicit = values != null || (selected != null && !selected.isBlank());
            Path configPath = explicit ? Path.of(selected) : Path.of("conf/driver.yml");
            ServiceConfig config = explicit ? VersionedConfigLoader.load(configPath, null)
                    : VersionedConfigLoader.loadDefault(Path.of("."), ServiceRole.DRIVER, null);
            if (config.role() != ServiceRole.DRIVER) {
                throw new IllegalArgumentException("Driver application requires role: driver");
            }
            Path parent = configPath.toAbsolutePath().normalize().getParent();
            Path installation = parent != null && Path.of("conf").equals(parent.getFileName())
                    ? parent.getParent() : parent;
            if (installation == null) {
                installation = Path.of(".").toAbsolutePath().normalize();
            }
            var application = new SpringApplication(DriverApplication.class);
            application.setWebApplicationType(config.web().enabled()
                    ? WebApplicationType.SERVLET : WebApplicationType.NONE);
            Path finalInstallation = installation;
            application.addInitializers(context -> {
                context.getBeanFactory().registerSingleton("serviceConfig", config);
                context.getBeanFactory().registerSingleton("installationDirectory", finalInstallation);
            });
            application.setDefaultProperties(Map.of(
                    "server.shutdown", "graceful",
                    "spring.lifecycle.timeout-per-shutdown-phase", config.shutdown().gracePeriod().toMillis() + "ms"));
            application.run(args);
        } catch (ConfigLoadException e) {
            System.err.println("Driver configuration failed [" + e.code() + "]: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Driver startup failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
