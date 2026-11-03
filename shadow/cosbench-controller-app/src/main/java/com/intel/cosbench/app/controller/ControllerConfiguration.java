package com.intel.cosbench.app.controller;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.DriverEndpoint;
import com.intel.cosbench.controller.web.ControllerWebConfiguration;
import com.intel.cosbench.controller.web.ControllerWebSecurityConfiguration;
import com.intel.cosbench.controller.port.DriverCommandClient;
import com.intel.cosbench.controller.port.DriverCommandClientProvider;
import com.intel.cosbench.controller.port.DriverCommandResponse;
import com.intel.cosbench.controller.model.ControllerContext;
import com.intel.cosbench.controller.model.DriverContext;
import com.intel.cosbench.controller.model.DriverRegistry;
import com.intel.cosbench.controller.service.COSBControllerService;
import com.intel.cosbench.controller.repository.RAMWorkloadRepository;
import com.intel.cosbench.http.AuthenticatedDriverClient;
import com.intel.cosbench.http.DriverClientRegistry;
import com.intel.cosbench.http.DriverCommandResult;
import com.intel.cosbench.http.HttpClientSettings;
import com.intel.cosbench.http.HttpTlsContext;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.LogLevel;
import com.intel.cosbench.log.LogManager;

@Configuration(proxyBeanMethods = false)
@Import({ControllerWebConfiguration.class, ControllerWebSecurityConfiguration.class})
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

    @Bean(destroyMethod = "close")
    DriverCommandClientProvider driverCommandClientProvider(ServiceConfig config, Path installationDirectory) {
        if (config.drivers().isEmpty()) {
            return new DriverCommandClientProvider() {
                @Override
                public DriverCommandClient client(String endpoint) {
                    throw new IllegalStateException("no Driver endpoint is configured");
                }

                @Override
                public void close() {
                }
            };
        }
        if (config.auth().token() == null) {
            throw new IllegalArgumentException("Controller-to-Driver calls require auth.token");
        }
        for (DriverEndpoint endpoint : config.drivers()) {
            if (!"https".equalsIgnoreCase(URI.create(endpoint.url()).getScheme())) {
                throw new IllegalArgumentException("Controller-to-Driver endpoints must use HTTPS");
            }
        }

        var tls = config.auth().tls();
        if (!tls.enabled() || tls.trustStore() == null) {
            throw new IllegalArgumentException("Controller-to-Driver calls require auth.tls.enabled and trustStore");
        }
        char[] password = tlsStorePassword();
        javax.net.ssl.SSLContext sslContext;
        try {
            sslContext = HttpTlsContext.create(tls,
                    tls.keyStore() == null ? null : resolveStore(tls.keyStore(), installationDirectory),
                    resolveStore(tls.trustStore(), installationDirectory), password);
        } finally {
            Arrays.fill(password, '\0');
        }
        HttpClientSettings settings = HttpClientSettings.from(config.http());
        DriverClientRegistry registry = DriverClientRegistry.pooled(
                settings, config.auth().token().value(), sslContext);
        return new DriverCommandClientProvider() {
            @Override
            public DriverCommandClient client(String endpoint) {
                URI address = URI.create(endpoint);
                return (command, payload, write, workloadDeadlineNanos) -> {
                    AuthenticatedDriverClient client = registry.client(address);
                    DriverCommandResult result = workloadDeadlineNanos == Long.MAX_VALUE
                            ? client.execute(command, payload, write, settings.commandTimeout())
                            : client.executeWorkloadCommand(command, payload, write, workloadDeadlineNanos);
                    return new DriverCommandResponse(result.status(), result.body());
                };
            }

            @Override
            public void close() {
                registry.close();
            }
        };
    }

    @Bean(name = {"controllerService", "controller"}, destroyMethod = "close")
    COSBControllerService controllerService(ServiceConfig config, Path installationDirectory,
            LogManager systemLogManager, DriverCommandClientProvider driverCommandClientProvider) throws IOException {
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
        context.setDriverCommandClientProvider(driverCommandClientProvider);
        context.setWorkloadTimeout(config.http().timeouts().workload());
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

    private static Path resolveStore(String configured, Path installationDirectory) {
        if (configured.startsWith("file:")) {
            return Path.of(URI.create(configured));
        }
        Path path = Path.of(configured);
        return path.isAbsolute() ? path.normalize() : installationDirectory.resolve(path).normalize();
    }

    private static char[] tlsStorePassword() {
        String file = System.getenv("COSBENCH_TLS_STORE_PASSWORD_FILE");
        try {
            String value = file == null || file.isBlank()
                    ? System.getenv("COSBENCH_TLS_STORE_PASSWORD")
                    : Files.readString(Path.of(file)).trim();
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("TLS store password must come from a protected runtime source");
            }
            return value.toCharArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read TLS store password file", failure);
        }
    }
}
