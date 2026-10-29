package com.intel.cosbench.log;

public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR;

    public static LogLevel parseLevel(String value) {
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
