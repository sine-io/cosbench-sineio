package com.intel.cosbench.http;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.KeyManagerFactory;

/** Builds per-client TLS state without mutating process-wide JVM properties. */
public final class HttpTlsContext {

    private HttpTlsContext() { }

    public static SSLContext create(com.intel.cosbench.config.runtime.TlsConfig config,
            Path keyStore, Path trustStore, char[] password) {
        if (config == null || !config.enabled()) {
            return null;
        }
        if ((config.keyStore() != null && keyStore == null)
                || (config.trustStore() != null && trustStore == null)) {
            throw new IllegalArgumentException("configured TLS store path was not resolved");
        }
        return create(keyStore, trustStore, password);
    }

    public static SSLContext create(Path keyStorePath, Path trustStorePath, char[] password) {
        if (keyStorePath == null && trustStorePath == null) {
            throw new IllegalArgumentException("a TLS key or trust store must be configured");
        }
        if ((keyStorePath != null || trustStorePath != null)
                && (password == null || password.length == 0)) {
            throw new IllegalArgumentException("TLS store password must be provided from a protected secret");
        }
        try {
            KeyManagerFactory keyManagers = null;
            if (keyStorePath != null) {
                KeyStore keyStore = load(keyStorePath, password);
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, password);
            }

            TrustManagerFactory trustManagers = null;
            if (trustStorePath != null) {
                KeyStore trustStore = load(trustStorePath, password);
                trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagers.init(trustStore);
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers == null ? null : trustManagers.getTrustManagers(), null);
            return context;
        } catch (Exception failure) {
            throw new IllegalArgumentException("cannot initialize configured TLS stores", failure);
        }
    }

    private static KeyStore load(Path path, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) {
            store.load(input, password);
        }
        return store;
    }
}
