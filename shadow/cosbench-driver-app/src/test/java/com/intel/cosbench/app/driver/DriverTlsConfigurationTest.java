package com.intel.cosbench.app.driver;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intel.cosbench.config.runtime.AuthConfig;
import com.intel.cosbench.config.runtime.ServiceConfig;
import com.intel.cosbench.config.runtime.TlsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

class DriverTlsConfigurationTest {
    @Test
    void listenerCannotStartWithoutTls() {
        ServiceConfig config = mock(ServiceConfig.class);
        when(config.auth()).thenReturn(new AuthConfig(null, new TlsConfig(false, null, null)));

        assertThatThrownBy(() -> new DriverConfiguration().driverServer(config, new MockEnvironment())
                .customize(new TomcatServletWebServerFactory()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Driver HTTP requires TLS");
    }
}
