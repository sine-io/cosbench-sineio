package com.intel.cosbench.driver.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver;

import jakarta.servlet.MultipartConfigElement;

import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.service.DriverService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class DriverWebConfiguration {

    @Bean
    FreeMarkerConfigurer driverTemplateConfigurer() {
        FreeMarkerConfigurer configurer = new FreeMarkerConfigurer();
        configurer.setTemplateLoaderPath("classpath:/templates/");
        configurer.setDefaultEncoding("UTF-8");
        Properties settings = new Properties();
        settings.setProperty("output_format", "HTML");
        settings.setProperty("auto_escaping_policy", "enable_if_supported");
        configurer.setFreemarkerSettings(settings);
        return configurer;
    }

    @Bean
    FreeMarkerViewResolver driverTemplateResolver() {
        FreeMarkerViewResolver resolver = new FreeMarkerViewResolver();
        resolver.setPrefix("");
        resolver.setSuffix(".ftl");
        resolver.setContentType("text/html;charset=UTF-8");
        resolver.setCache(true);
        resolver.setOrder(1);
        return resolver;
    }

    @Bean
    MultipartResolver driverMultipartResolver() {
        return new StandardServletMultipartResolver();
    }

    @Bean
    MultipartConfigElement driverMultipartConfig(ServiceConfig config) {
        long maxUploadBytes = config.web().maxUploadBytes();
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(maxUploadBytes));
        factory.setMaxRequestSize(DataSize.ofBytes(maxUploadBytes + 1024 * 1024));
        return factory.createMultipartConfig();
    }

    @Bean
    SimpleUrlHandlerMapping driverPageRoutes(DriverService driver) {
        IndexPageController index = wired(new IndexPageController(), driver, IndexPageController::setDriver);
        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("/", index);
        routes.put("/index.html", index);
        routes.put("/submit.html", wired(new SubmitPageController(), driver, SubmitPageController::setDriver));
        routes.put("/mission.html", wired(new MissionPageController(), driver, MissionPageController::setDriver));
        routes.put("/workers.html", wired(new WorkersPageController(), driver, WorkersPageController::setDriver));
        routes.put("/submit-mission.do",
                wired(new MissionSubmissionController(), driver, MissionSubmissionController::setDriver));
        routes.put("/perform-login.do", wired(new PerformLoginController(), driver, PerformLoginController::setDriver));
        routes.put("/launch-mission.do", wired(new LaunchMissionController(), driver, LaunchMissionController::setDriver));
        routes.put("/close-mission.do", wired(new CloseMissionController(), driver, CloseMissionController::setDriver));
        routes.put("/abort-mission.do", wired(new AbortMissionController(), driver, AbortMissionController::setDriver));
        routes.put("/download-log.do", wired(new DownloadLogController(), driver, DownloadLogController::setDriver));

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(routes);
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return mapping;
    }

    @Bean
    DriverErrorController driverErrorController() {
        return new DriverErrorController();
    }

    @Bean
    TriggerController driverTriggerController(DriverService driver) {
        return new TriggerController(driver);
    }

    private static <T> T wired(T handler, DriverService driver,
            java.util.function.BiConsumer<T, DriverService> setter) {
        setter.accept(handler, driver);
        return handler;
    }
}
