package com.intel.cosbench.app.driver;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.TreeSet;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import com.intel.cosbench.api.provider.StaticProviderRegistry;
import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.driver.model.DriverContext;
import com.intel.cosbench.driver.service.COSBAuthAPIService;
import com.intel.cosbench.driver.service.COSBDriverService;
import com.intel.cosbench.driver.service.COSBStorageAPIService;
import com.intel.cosbench.http.DriverCommandController;
import com.intel.cosbench.http.IdempotencyStore;
import com.intel.cosbench.http.LegacyDriverCommandPort;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.LogLevel;
import com.intel.cosbench.log.LogManager;
import com.intel.cosbench.driver.web.DriverWebConfiguration;

@Configuration(proxyBeanMethods = false)
@Import(DriverWebConfiguration.class)
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
                expectedProviders(environment.getProperty("cosbench.providers.expected.storage", "sio,siov1,gdas,s3")),
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
        context.setUrl((config.auth().tls().enabled() ? "https://" : "http://")
                + config.listen().host() + ":" + config.listen().port()
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
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> driverServer(ServiceConfig config,
            Path installationDirectory, Environment environment) {
        return factory -> {
            if (!config.auth().tls().enabled()) {
                throw new IllegalArgumentException("Driver HTTP requires TLS: set auth.tls.enabled to true");
            }
            try {
                factory.setAddress(InetAddress.getByName(config.listen().host()));
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot resolve listen.host " + config.listen().host(), e);
            }
            factory.setPort(config.listen().port());
            factory.setContextPath(config.listen().contextPath());
            String keyStore = config.auth().tls().keyStore();
            if (keyStore == null) {
                throw new IllegalArgumentException("Driver TLS requires auth.tls.keyStore");
            }
            Ssl ssl = factory.getSsl();
            if (ssl == null) {
                ssl = new Ssl();
            }
            ssl.setEnabled(true);
            ssl.setKeyStore(resolveKeyStore(keyStore, installationDirectory));
            ssl.setKeyStorePassword(environment.getProperty("server.ssl.key-store-password"));
            ssl.setKeyStoreType(environment.getProperty("server.ssl.key-store-type", "PKCS12"));
            ssl.setKeyPassword(environment.getProperty("server.ssl.key-password"));
            factory.setSsl(ssl);
        };
    }

    private static String resolveKeyStore(String configured, Path installationDirectory) {
        Path path = configured.startsWith("file:") ? Path.of(URI.create(configured)) : Path.of(configured);
        return (path.isAbsolute() ? path : installationDirectory.resolve(path)).normalize().toString();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    DriverCommandController driverCommands(ServiceConfig config, COSBDriverService driver) {
        return DriverCommandController.from(config, new LegacyDriverCommandPort(driver), new IdempotencyStore(),
                driver::isAccepting);
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
