package com.intel.cosbench.config.runtime;

/** Service-to-service authentication, intentionally independent from page sessions. */
public record AuthConfig(TokenConfig token, TlsConfig tls) {

    public AuthConfig {
        tls = tls == null ? new TlsConfig(false, null, null) : tls;
    }
}
