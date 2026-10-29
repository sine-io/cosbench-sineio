package com.intel.cosbench.shadow.web;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.config.runtime.DriverEndpoint;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.ServiceRole;
import com.intel.cosbench.controller.model.ControllerContext;
import com.intel.cosbench.controller.model.DriverContext;
import com.intel.cosbench.controller.model.DriverRegistry;
import com.intel.cosbench.controller.service.COSBControllerService;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.LogLevel;
import com.intel.cosbench.service.ControllerService;

@Configuration
public class LegacyControllerServiceBootstrap {

    @Bean(destroyMethod = "close")
    ControllerService controllerService(ServiceConfig config, @Value("${cosbench.config}") String configPath)
            throws IOException {
        if (config.role() != ServiceRole.CONTROLLER) {
            throw new IllegalArgumentException("cosbench.config must have role: controller");
        }
        CastorConfigTools.getWorkloadResolver();

        Path baseDirectory = baseDirectory(Path.of(configPath));
        configureLegacyLogging(config, baseDirectory);
        DriverRegistry drivers = new DriverRegistry();
        for (DriverEndpoint endpoint : config.drivers()) {
            DriverContext driver = new DriverContext();
            driver.setName(endpoint.name());
            driver.setUrl(endpoint.url());
            driver.setAliveState(false);
            drivers.addDriver(driver);
        }

        ControllerContext context = new ControllerContext();
        context.setName(config.name());
        context.setUrl(controllerUrl(config));
        context.setArchive_dir(config.paths().archiveAt(baseDirectory).toString());
        context.setConcurrency(config.concurrency());
        context.setDriverRegistry(drivers);
        context.setVersion("Boot 3 Jakarta spike");

        COSBControllerService service = new COSBControllerService();
        service.setContext(context);
        service.init();
        return service;
    }

    private static void configureLegacyLogging(ServiceConfig config, Path baseDirectory) throws IOException {
        var logManager = LogFactory.getSystemLogManager();
        Path logFile = config.paths().resolve(baseDirectory, config.logging().file());
        Files.createDirectories(logFile.getParent());
        logManager.setLogFile(logFile.getParent().toFile(), logFile.getFileName().toString(), true, false);
        if (!"OFF".equals(config.logging().level())) {
            logManager.setLogLevel(LogLevel.parseLevel(config.logging().level()));
        }
        logManager.enableConsole();
    }

    private static String controllerUrl(ServiceConfig config) {
        String contextPath = config.listen().contextPath();
        return URI.create("http://" + config.listen().host() + ":" + config.listen().port()
                + contextPath).toString();
    }

    private static Path baseDirectory(Path configPath) {
        Path absolute = configPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        return parent != null && "conf".equals(parent.getFileName().toString()) && parent.getParent() != null
                ? parent.getParent()
                : parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }
}
