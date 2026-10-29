package com.intel.cosbench.client.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HttpClientUtilContractTest {

    @Test
    void encodesPathValuesAndCreatesTheSupportedLegacyMethods() {
        assertEquals("a%20b%2Bc", HttpClientUtil.encodeURL("a b+c"));
        assertEquals("GET", HttpClientUtil.makeHttpGet("http://localhost/").getMethod());
        assertEquals("PUT", HttpClientUtil.makeHttpPut("http://localhost/").getMethod());
        assertEquals("POST", HttpClientUtil.makeHttpPost("http://localhost/").getMethod());
        assertEquals("HEAD", HttpClientUtil.makeHttpHead("http://localhost/").getMethod());
        assertEquals("DELETE", HttpClientUtil.makeHttpDelete("http://localhost/").getMethod());
    }
}
