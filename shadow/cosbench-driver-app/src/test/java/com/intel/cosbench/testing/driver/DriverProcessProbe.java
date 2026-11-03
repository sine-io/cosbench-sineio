package com.intel.cosbench.testing.driver;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.intel.cosbench.service.DriverService;

/** Test-only endpoint exposing Driver mission state to the process replay. */
public final class DriverProcessProbe {
    @Configuration(proxyBeanMethods = false)
    @Import(ProbeController.class)
    public static class ProbeConfiguration {
    }

    @RestController
    public static class ProbeController {
        private final DriverService service;

        public ProbeController(DriverService service) {
            this.service = service;
        }

        @GetMapping("/_probe/missions/{id}")
        Map<String, String> mission(@PathVariable("id") String id) {
            var mission = service.getMissionInfo(id);
            if (mission == null) {
                throw new IllegalArgumentException("unknown mission " + id);
            }
            return Map.of("id", id, "state", mission.getState().name());
        }
    }
}
