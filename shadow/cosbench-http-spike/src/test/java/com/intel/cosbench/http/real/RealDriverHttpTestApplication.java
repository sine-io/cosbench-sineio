package com.intel.cosbench.http.real;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import com.intel.cosbench.http.DriverCommandController;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({DriverCommandController.class, RealDriverHttpContractTest.Config.class,
        RealDriverHttpContractTest.DriverHttpConfiguration.class})
class RealDriverHttpTestApplication {
}
