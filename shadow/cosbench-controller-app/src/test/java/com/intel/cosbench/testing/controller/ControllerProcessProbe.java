package com.intel.cosbench.testing.controller;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.nio.file.Files;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.service.ControllerService;

/** Test-only HTTP adapter to the real public service. Never included in the application JAR. */
public final class ControllerProcessProbe {
    @Configuration(proxyBeanMethods = false)
    @Import(ProbeController.class)
    public static class ProbeConfiguration {
    }

    @RestController
    public static class ProbeController {
        private final ControllerService service;
        private final ControllerService legacyBean;

        public ProbeController(ControllerService service, @Qualifier("controller") ControllerService legacyBean) {
            this.service = service;
            this.legacyBean = legacyBean;
        }

        @Order(Ordered.LOWEST_PRECEDENCE)
        @EventListener(ContextClosedEvent.class)
        void verifyAdmissionOnTerm() {
            String xml = "<workload name=\"late\"><workflow><workstage name=\"delay\" closuredelay=\"1\">"
                    + "<work type=\"delay\"/></workstage></workflow></workload>";
            try {
                service.submit(new XmlConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
                System.out.println("PROBE_TERM_ADMISSION_ACCEPTED");
            } catch (java.util.concurrent.RejectedExecutionException expected) {
                System.out.println("PROBE_TERM_ADMISSION_REJECTED");
            }
        }

        @GetMapping("/_probe/info")
        Map<String, Object> info() {
            return Map.of("name", service.getControllerInfo().getName(), "compatibleBean", service == legacyBean,
                    "accepting", ((com.intel.cosbench.controller.service.COSBControllerService) service).isAccepting());
        }

        @PostMapping("/_probe/workloads")
        Map<String, Object> submit(@RequestBody String xml, @RequestParam(name = "fire", defaultValue = "false") boolean fire) {
            String id = service.submit(new XmlConfig(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            if (fire) {
                service.fire(id);
            }
            return Map.of("id", id, "state", service.getWorkloadInfo(id).getState().name());
        }

        @PostMapping("/_probe/close")
        Map<String, Object> closeTwice() throws Exception {
            ((AutoCloseable) service).close();
            ((AutoCloseable) service).close();
            return Map.of("closed", true);
        }

        @GetMapping("/_probe/archive")
        List<Map<String, String>> archive() {
            service.setloadArch(true);
            return Arrays.stream(service.getArchivedWorkloads()).map(workload -> {
                try {
                    return Map.of("id", workload.getId(), "state", workload.getState().name(),
                            "configuration", Files.readString(service.getWorkloadConfig(workload).toPath()),
                            "log", Files.readString(service.getWorkloadLog(workload).toPath()));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }

        @GetMapping("/_probe/workloads/{id}")
        Map<String, Object> workload(@PathVariable("id") String id) {
            var workload = service.getWorkloadInfo(id);
            return Map.of("id", workload.getId(), "state", workload.getState().name());
        }
    }
}
