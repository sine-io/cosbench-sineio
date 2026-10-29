package com.intel.cosbench.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;
import org.junit.jupiter.api.Test;

class DriverResponseSerializationTest {

    @Test
    void submitResponseKeepsTheLegacyDriverJsonFieldsAndDefaults() throws Exception {
        SubmitResponse response = new SubmitResponse();
        response.setId("mission-17");

        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(Inclusion.NON_NULL);

        assertEquals("{\"code\":200,\"succ\":true,\"id\":\"mission-17\"}",
                mapper.writeValueAsString(response));
    }
}
