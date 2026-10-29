package com.intel.cosbench.shadow.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver;

import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.VersionedConfigLoader;

import jakarta.servlet.MultipartConfigElement;

@Configuration
public class ControllerWebConfiguration {

    @Bean
    FreeMarkerConfigurer legacyTemplateConfigurer() {
        FreeMarkerConfigurer configurer = new FreeMarkerConfigurer();
        configurer.setTemplateLoaderPath("classpath:/templates/");
        configurer.setDefaultEncoding("UTF-8");
        return configurer;
    }

    @Bean
    FreeMarkerViewResolver legacyTemplateResolver() {
        FreeMarkerViewResolver resolver = new FreeMarkerViewResolver();
        resolver.setPrefix("");
        resolver.setSuffix(".ftl");
        resolver.setContentType("text/html;charset=UTF-8");
        resolver.setCache(true);
        resolver.setOrder(1);
        return resolver;
    }

    @Bean
    ServiceConfig serviceConfig(@Value("${cosbench.config}") String configPath) {
        return VersionedConfigLoader.load(Path.of(configPath), null);
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> controllerServer(ServiceConfig config) {
        return factory -> {
            try {
                factory.setAddress(InetAddress.getByName(config.listen().host()));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("cannot resolve listen.host " + config.listen().host(), e);
            }
            factory.setPort(config.listen().port());
            factory.setContextPath(config.listen().contextPath());
        };
    }

    @Bean
    MultipartConfigElement multipartConfig(ServiceConfig config) {
        long maxUploadBytes = config.web().maxUploadBytes();
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(maxUploadBytes));
        factory.setMaxRequestSize(DataSize.ofBytes(maxUploadBytes + 1024 * 1024));
        return factory.createMultipartConfig();
    }
}
