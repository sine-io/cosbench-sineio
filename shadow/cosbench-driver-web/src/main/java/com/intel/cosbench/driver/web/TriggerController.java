package com.intel.cosbench.driver.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.intel.cosbench.service.DriverService;

@RestController
public class TriggerController {

    private final DriverService driver;

    public TriggerController(DriverService driver) {
        this.driver = driver;
    }

    @GetMapping(value = "/run-trigger.do", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> runTrigger(@RequestParam(name = "command", required = false) String command) {
        String output = command == null || command.isBlank() ? "" : driver.trigger(command).getScriptLog();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(output);
    }
}
