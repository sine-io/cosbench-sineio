package com.intel.cosbench.config.castor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.intel.cosbench.config.Workload;
import com.intel.cosbench.config.XmlConfig;

class LegacyXmlContractTest {

    @Test
    void workloadXmlParsesAndSerializesWithoutChangingItsExecutionSemantics() throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/workload-contract.xml")) {
            assertNotNull(source);
            Workload workload = parse(source.readAllBytes());
            assertEquals("shared-module-contract", workload.getName());
            assertEquals("mock", workload.getAuth().getType());
            assertEquals("mock", workload.getStorage().getType());
            assertEquals(1, workload.getWorkflow().getStages().size());
            assertEquals("read", workload.getWorkflow().getStages().getFirst().getWorks().getFirst().getName());
            assertEquals(10, workload.getWorkflow().getStages().getFirst().getWorks().getFirst().getTotalOps());

            String xml = CastorConfigTools.getWorkloadWriter().toXmlString(workload);
            Workload roundTrip = parse(xml.getBytes(StandardCharsets.UTF_8));
            assertEquals("shared-module-contract", roundTrip.getName());
            assertEquals(10, roundTrip.getWorkflow().getStages().getFirst().getWorks().getFirst().getTotalOps());
        }
    }

    private static Workload parse(byte[] xml) {
        return CastorConfigTools.getWorkloadResolver().toWorkload(
                new XmlConfig(new ByteArrayInputStream(xml)));
    }
}
