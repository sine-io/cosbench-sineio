package com.intel.cosbench.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingContractTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void formatsParameterizedMessagesAndReleasesItsLogManager() throws Exception {
        Path logFile = temporaryDirectory.resolve("system.log");
        LogManager manager = LogFactory.createLogManager();
        manager.setLogFile(temporaryDirectory.toFile(), logFile.getFileName().toString(), false, false);
        manager.getLogger().info("mission {} accepted by {}", "mission-17", "driver-1");
        manager.dispose();
        manager.getLogger().info("message after disposal");

        String contents = Files.readString(logFile);
        assertTrue(contents.contains("mission mission-17 accepted by driver-1"));
        assertFalse(contents.contains("message after disposal"));
    }
}
