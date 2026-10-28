package com.intel.cosbench.config.castor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.intel.cosbench.config.XmlConfig;
import org.junit.jupiter.api.Test;

class CastorMappingsPackagingTest {

    @Test
    void registeredMappingResourcesAreReadableFromTheModuleClasspath() throws Exception {
        for (String model : List.of("auth", "storage", "operation", "work", "mission", "stage", "workflow", "workload")) {
            String resource = "castor/" + model + "-mapping.xml";
            try (InputStream input = XmlConfig.class.getResourceAsStream(resource)) {
                assertNotNull(input, resource + " is missing from the module classpath");
                XmlConfig config = new XmlConfig(input);
                assertTrue(config.hasContent());
                assertEquals("<stream>", config.getPath());
                String xml = new String(config.getContent().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(xml.contains("<mapping>"), resource + " is not a Castor mapping");
                assertTrue(xml.contains("</mapping>"), resource + " has no closing mapping element");
            }
        }
    }
}
