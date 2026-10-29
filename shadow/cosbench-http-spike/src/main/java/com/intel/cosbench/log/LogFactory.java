package com.intel.cosbench.log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;

/** Minimal no-op logger bridge for compiling the legacy DriverService spike. */
public final class LogFactory {

    private static final Logger LOGGER = (Logger) Proxy.newProxyInstance(
            Logger.class.getClassLoader(), new Class<?>[] { Logger.class },
            (proxy, method, args) -> method.getName().startsWith("is") ? false : null);
    private static final LogManager MANAGER = new LogManager() {
        @Override public void dispose() { }
        @Override public Logger getLogger() { return LOGGER; }
        @Override public LogLevel getLogLevel() { return LogLevel.INFO; }
        @Override public void setLogLevel(LogLevel level) { }
        @Override public void setLogFile(File dir, String filename, boolean append, boolean buffer) throws IOException { }
        @Override public void enableConsole() { }
        @Override public String getLogAsString() { return ""; }
    };

    private LogFactory() { }

    public static Logger getSystemLogger() {
        return LOGGER;
    }

    public static LogManager createLogManager() {
        return MANAGER;
    }

    public static LogManager getSystemLogManager() {
        return MANAGER;
    }
}
