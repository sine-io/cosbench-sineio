package com.intel.cosbench.app.driver;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.TreeSet;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.driver.model.DriverContext;
import com.intel.cosbench.driver.service.COSBAuthAPIService;
import com.intel.cosbench.driver.service.COSBDriverService;
import com.intel.cosbench.driver.service.COSBStorageAPIService;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.LogLevel;
import com.intel.cosbench.log.LogManager;

@Configuration(proxyBeanMethods = false)
public class DriverConfiguration {
    @Bean(destroyMethod = "dispose")
    LogManager systemLogManager(ServiceConfig config, Path installationDirectory) throws IOException {
        var manager = LogFactory.getSystemLogManager();
        Path log = config.paths().resolve(installationDirectory, config.logging().file());
        Files.createDirectories(log.getParent());
        Files.createDirectories(config.paths().missionAt(installationDirectory));
        manager.setLogLevel(LogLevel.parseLevel(config.logging().level()));
        manager.setLogFile(log.getParent().toFile(), log.getFileName().toString(), true, false);
        manager.enableConsole();
        return manager;
    }

    @Bean
    StaticProviderRegistry providerRegistry(Environment environment) {
        return StaticProviderRegistry.discover(
                Thread.currentThread().getContextClassLoader(),
                expectedProviders(environment.getProperty("cosbench.providers.expected.storage", "")),
                expectedProviders(environment.getProperty("cosbench.providers.expected.auth", "")));
    }

    @Bean(name = {"driverService", "driver"}, destroyMethod = "close")
    COSBDriverService driverService(
            ServiceConfig config,
            Path installationDirectory,
            StaticProviderRegistry providerRegistry,
            LogManager systemLogManager) throws IOException {
        CastorConfigTools.getMissionResolver();
        Path missionDirectory = config.paths().missionAt(installationDirectory);
        Files.createDirectories(missionDirectory);

        DriverContext context = new DriverContext();
        context.setName(config.name());
        context.setUrl("http://" + config.listen().host() + ":" + config.listen().port()
                + config.listen().contextPath());
        context.setMission_dir(missionDirectory.toString());
        context.setScripts_dir(installationDirectory.resolve("scripts").toString());
        context.setVersion("0.1.0-SNAPSHOT");

        COSBDriverService service = new COSBDriverService(
                context,
                new COSBAuthAPIService(providerRegistry.authFactories()),
                new COSBStorageAPIService(providerRegistry.storageFactories()),
                config.shutdown().gracePeriod());
        service.init();
        return service;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> driverServer(ServiceConfig config) {
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

    private static Collection<String> expectedProviders(String configured) {
        TreeSet<String> names = new TreeSet<>();
        Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
        return names;
    }
}
