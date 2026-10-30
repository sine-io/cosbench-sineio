package com.intel.cosbench.app.controller;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.controller.model.ControllerContext;
import com.intel.cosbench.controller.model.DriverContext;
import com.intel.cosbench.controller.model.DriverRegistry;
import com.intel.cosbench.controller.service.COSBControllerService;
import com.intel.cosbench.controller.repository.RAMWorkloadRepository;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.LogLevel;
import com.intel.cosbench.log.LogManager;

@Configuration(proxyBeanMethods = false)
public class ControllerConfiguration {
    @Bean(destroyMethod = "dispose")
    LogManager systemLogManager(ServiceConfig config, Path installationDirectory) throws IOException {
        var manager = LogFactory.getSystemLogManager();
        Path log = config.paths().resolve(installationDirectory, config.logging().file());
        Files.createDirectories(log.getParent());
        manager.setLogLevel(LogLevel.parseLevel(config.logging().level()));
        manager.setLogFile(log.getParent().toFile(), log.getFileName().toString(), true, false);
        manager.enableConsole();
        return manager;
    }

    @Bean(name = {"controllerService", "controller"}, destroyMethod = "close")
    COSBControllerService controllerService(ServiceConfig config, Path installationDirectory,
            LogManager systemLogManager) throws IOException {
        Files.createDirectories(config.paths().archiveAt(installationDirectory));
        Files.createDirectories(config.paths().workspaceAt(installationDirectory));
        CastorConfigTools.getWorkloadResolver();
        var drivers = new DriverRegistry();
        for (var endpoint : config.drivers()) {
            var driver = new DriverContext();
            driver.setName(endpoint.name());
            driver.setUrl(endpoint.url());
            driver.setAliveState(false);
            drivers.addDriver(driver);
        }
        var context = new ControllerContext();
        context.setName(config.name());
        context.setUrl("http://" + config.listen().host() + ":" + config.listen().port() + config.listen().contextPath());
        context.setArchive_dir(config.paths().archiveAt(installationDirectory).toString());
        context.setConcurrency(config.concurrency());
        context.setDriverRegistry(drivers);
        context.setVersion("0.1.0-SNAPSHOT");
        var service = new COSBControllerService(new RAMWorkloadRepository(installationDirectory.toFile()));
        service.setShutdownGracePeriod(config.shutdown().gracePeriod());
        service.setContext(context);
        service.init();
        return service;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> controllerServer(ServiceConfig config) {
        return factory -> {
            try {
                factory.setAddress(InetAddress.getByName(config.listen().host()));
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot resolve listen.host " + config.listen().host(), e);
            }
            factory.setPort(config.listen().port());
            factory.setContextPath(config.listen().contextPath());
        };
    }
}
