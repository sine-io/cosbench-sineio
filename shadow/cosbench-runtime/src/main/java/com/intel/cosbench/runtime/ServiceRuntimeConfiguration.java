package com.intel.cosbench.runtime;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.ServiceRole;
import com.intel.cosbench.config.runtime.VersionedConfigLoader;

/** Composition root for the configuration and lifecycle seam. */
@Configuration
public class ServiceRuntimeConfiguration {

    @Bean
    ServiceConfig serviceConfig(@Value("${cosbench.config}") String configPath) {
        return VersionedConfigLoader.load(Path.of(configPath), null);
    }

    @Bean
    ArchiveReader archiveReader(ServiceConfig config, @Value("${cosbench.config}") String configPath) {
        return new ArchiveReader(baseDirectory(Path.of(configPath)), config.paths());
    }

    @Bean
    ServiceRuntime serviceRuntime(ServiceConfig config, ArchiveReader archiveReader) {
        return new ServiceRuntime(config, archiveReader);
    }

    @Bean
    RuntimeHealthController runtimeHealthController(ServiceRuntime runtime, ArchiveReader archiveReader) {
        return new RuntimeHealthController(runtime, archiveReader);
    }

    @Bean
    RuntimeTaskController runtimeTaskController(ServiceRuntime runtime) {
        return new RuntimeTaskController(runtime);
    }

    @Bean
    static org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> runtimeReady(
            ServiceRuntime runtime) {
        return event -> runtime.markReady();
    }

    @Bean
    static org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory> serverCustomizer(
            ServiceConfig config) {
        return factory -> {
            try {
                factory.setAddress(java.net.InetAddress.getByName(config.listen().host()));
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("cannot resolve listen.host " + config.listen().host(), e);
            }
            factory.setPort(config.listen().port());
            factory.setContextPath(config.listen().contextPath());
        };
    }

    private static Path baseDirectory(Path configPath) {
        Path absolute = configPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        return parent != null && "conf".equals(parent.getFileName().toString()) && parent.getParent() != null
                ? parent.getParent()
                : parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }
}
