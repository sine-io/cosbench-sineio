package com.intel.cosbench.app.controller;

import java.nio.file.Path;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.DefaultApplicationArguments;

import com.intel.cosbench.config.runtime.ConfigLoadException;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.ServiceRole;
import com.intel.cosbench.config.runtime.VersionedConfigLoader;

/** Controller composition root; only the entry point assigns a process exit status. */
@SpringBootApplication
public class ControllerApplication {
    public static void main(String[] args) {
        try {
            var arguments = new DefaultApplicationArguments(args);
            var values = arguments.getOptionValues("cosbench.config");
            String selected = values == null ? System.getenv("COSBENCH_CONFIG")
                    : values.isEmpty() ? "" : values.getLast();
            boolean explicit = values != null || (selected != null && !selected.isBlank());
            Path configPath = explicit ? Path.of(selected) : Path.of("conf/controller.yml");
            ServiceConfig config = explicit ? VersionedConfigLoader.load(configPath, null)
                    : VersionedConfigLoader.loadDefault(Path.of("."), ServiceRole.CONTROLLER, null);
            if (config.role() != ServiceRole.CONTROLLER) {
                throw new IllegalArgumentException("Controller application requires role: controller");
            }
            Path parent = configPath.toAbsolutePath().normalize().getParent();
            Path installation = Path.of("conf").equals(parent.getFileName()) ? parent.getParent() : parent;
            var application = new SpringApplication(ControllerApplication.class);
            application.setWebApplicationType(config.web().enabled() ? WebApplicationType.SERVLET : WebApplicationType.NONE);
            application.addInitializers(context -> {
                context.getBeanFactory().registerSingleton("serviceConfig", config);
                context.getBeanFactory().registerSingleton("installationDirectory", installation);
            });
            application.setDefaultProperties(Map.of(
                    "server.shutdown", "graceful",
                    "spring.lifecycle.timeout-per-shutdown-phase", config.shutdown().gracePeriod().toMillis() + "ms"));
            application.run(args);
        } catch (ConfigLoadException e) {
            System.err.println("Controller configuration failed [" + e.code() + "]: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Controller startup failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
