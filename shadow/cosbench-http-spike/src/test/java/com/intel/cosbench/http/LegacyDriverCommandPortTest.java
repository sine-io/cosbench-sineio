package com.intel.cosbench.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intel.cosbench.bench.Snapshot;
import com.intel.cosbench.model.MissionInfo;
import com.intel.cosbench.service.DriverService;
import org.junit.jupiter.api.Test;

class LegacyDriverCommandPortTest {
    @Test
    void queryUsesOneConsistentSnapshot() throws Exception {
        DriverService driver = mock(DriverService.class);
        MissionInfo mission = mock(MissionInfo.class);
        Snapshot snapshot = new Snapshot();
        snapshot.setVersion(7);
        snapshot.setMinVersion(7);
        snapshot.setMaxVersion(7);
        when(driver.getMissionInfo("mission")).thenReturn(mission);
        when(mission.getSnapshot()).thenReturn(snapshot);

        DriverCommandResult result = new LegacyDriverCommandPort(driver)
                .query("mission".getBytes(StandardCharsets.UTF_8));

        JsonNode response = new ObjectMapper().readTree(result.body());
        assertThat(result.status()).isEqualTo(200);
        assertThat(response.path("version").asInt()).isEqualTo(7);
        assertThat(response.path("minVersion").asInt()).isEqualTo(7);
        assertThat(response.path("maxVersion").asInt()).isEqualTo(7);
        verify(mission).getSnapshot();
    }

    @Test
    void invalidPingRetainsLegacyDiagnostic() throws Exception {
        DriverCommandResult result = new LegacyDriverCommandPort(mock(DriverService.class))
                .ping("invalid".getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(500);
        assertThat(new ObjectMapper().readTree(result.body()).path("error").asText())
                .contains("invalid");
    }
}
