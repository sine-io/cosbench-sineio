package com.intel.cosbench.controller.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import jakarta.servlet.MultipartConfigElement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.servlet.view.freemarker.FreeMarkerConfigurer;
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver;

import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.controller.handler.CancelHandler;
import com.intel.cosbench.controller.handler.ConfigHandler;
import com.intel.cosbench.controller.handler.IndexHandler;
import com.intel.cosbench.controller.handler.SubmitHandler;
import com.intel.cosbench.controller.handler.WorkloadHandler;
import com.intel.cosbench.service.ControllerService;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
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
    MultipartConfigElement controllerMultipartConfig(ServiceConfig config) {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        long maxUploadBytes = config.web().maxUploadBytes();
        factory.setMaxFileSize(DataSize.ofBytes(maxUploadBytes));
        factory.setMaxRequestSize(DataSize.ofBytes(maxUploadBytes + 1024 * 1024));
        return factory.createMultipartConfig();
    }

    @Bean
    SimpleUrlHandlerMapping controllerLegacyRoutes(ControllerService controller) {
        Map<String, Object> routes = new LinkedHashMap<>();
        var root = new ParameterizableViewController();
        root.setViewName("redirect:/index.html");
        routes.put("/", root);

        routes.put("/logon.html", wired(new LogonPageController(), controller,
                LogonPageController::setController));
        routes.put("/logonError.html", wired(new LogonErrorPageController(), controller,
                LogonErrorPageController::setController));
        routes.put("/index.html", wired(new IndexPageController(), controller,
                IndexPageController::setController));
        routes.put("/matrix.html", wired(new MatrixPageController(), controller,
                MatrixPageController::setController));
        routes.put("/submit.html", wired(new SubmitPageController(), controller,
                SubmitPageController::setController));
        routes.put("/submit-workload.do", wired(new WorkloadSubmissionController(), controller,
                WorkloadSubmissionController::setController));
        routes.put("/config.html", wired(new ConfigPageController(), controller,
                ConfigPageController::setController));
        routes.put("/config-workload.do", wired(new WorkloadConfigurationController(), controller,
                WorkloadConfigurationController::setController));
        routes.put("/advanced-config.html", wired(new AdvancedConfigPageController(), controller,
                AdvancedConfigPageController::setController));
        routes.put("/advanced-config-workload.do", wired(new WorkloadMatrixConfigurationController(), controller,
                WorkloadMatrixConfigurationController::setController));
        routes.put("/workload.html", wired(new WorkloadPageController(), controller,
                WorkloadPageController::setController));
        routes.put("/cancel-workload.do", wired(new CancelWorkloadController(), controller,
                CancelWorkloadController::setController));

        routes.put("/cli/index.action", wired(new IndexHandler(), controller, IndexHandler::setController));
        routes.put("/cli/submit.action", wired(new SubmitHandler(), controller, SubmitHandler::setController));
        routes.put("/cli/config.action", wired(new ConfigHandler(), controller, ConfigHandler::setController));
        routes.put("/cli/workload.action", wired(new WorkloadHandler(), controller, WorkloadHandler::setController));
        routes.put("/cli/cancel.action", wired(new CancelHandler(), controller, CancelHandler::setController));

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(routes);
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return mapping;
    }

    private static <T> T wired(T handler, ControllerService controller,
            BiConsumer<T, ControllerService> setter) {
        setter.accept(handler, controller);
        return handler;
    }
}
