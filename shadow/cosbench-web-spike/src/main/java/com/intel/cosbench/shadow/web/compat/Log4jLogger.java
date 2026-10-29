package com.intel.cosbench.log.log4j;

import org.apache.log4j.Level;
import org.slf4j.helpers.MessageFormatter;

import com.intel.cosbench.log.Logger;

/** Boot 3 uses SLF4J 2, whose formatter returns a tuple instead of a string. */
public class Log4jLogger implements Logger {

    private static final String FQCN = Log4jLogger.class.getName();

    private final org.apache.log4j.Logger logger;

    Log4jLogger(org.apache.log4j.Logger logger) {
        this.logger = logger;
    }

    private String format(String pattern, Object... arguments) {
        return MessageFormatter.arrayFormat(pattern, arguments).getMessage();
    }

    private void log(Level level, String message, Throwable cause) {
        logger.log(FQCN, level, message, cause);
    }

    @Override
    public boolean isTraceEnabled() {
        return logger.isTraceEnabled();
    }

    @Override
    public void trace(String message) {
        log(Level.TRACE, message, null);
    }

    @Override
    public void trace(String message, Throwable cause) {
        log(Level.TRACE, message, cause);
    }

    @Override
    public void trace(String pattern, Object argument) {
        if (isTraceEnabled()) {
            log(Level.TRACE, format(pattern, argument), null);
        }
    }

    @Override
    public void trace(String pattern, Object first, Object second) {
        if (isTraceEnabled()) {
            log(Level.TRACE, format(pattern, first, second), null);
        }
    }

    @Override
    public void trace(String pattern, Object[] arguments) {
        if (isTraceEnabled()) {
            log(Level.TRACE, format(pattern, arguments), null);
        }
    }

    @Override
    public boolean isDebugEnabled() {
        return logger.isDebugEnabled();
    }

    @Override
    public void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    @Override
    public void debug(String message, Throwable cause) {
        log(Level.DEBUG, message, cause);
    }

    @Override
    public void debug(String pattern, Object argument) {
        if (isDebugEnabled()) {
            log(Level.DEBUG, format(pattern, argument), null);
        }
    }

    @Override
    public void debug(String pattern, Object first, Object second) {
        if (isDebugEnabled()) {
            log(Level.DEBUG, format(pattern, first, second), null);
        }
    }

    @Override
    public void debug(String pattern, Object[] arguments) {
        if (isDebugEnabled()) {
            log(Level.DEBUG, format(pattern, arguments), null);
        }
    }

    @Override
    public boolean isInfoEnabled() {
        return logger.isInfoEnabled();
    }

    @Override
    public void info(String message) {
        log(Level.INFO, message, null);
    }

    @Override
    public void info(String message, Throwable cause) {
        log(Level.INFO, message, cause);
    }

    @Override
    public void info(String pattern, Object argument) {
        if (isInfoEnabled()) {
            log(Level.INFO, format(pattern, argument), null);
        }
    }

    @Override
    public void info(String pattern, Object first, Object second) {
        if (isInfoEnabled()) {
            log(Level.INFO, format(pattern, first, second), null);
        }
    }

    @Override
    public void info(String pattern, Object[] arguments) {
        if (isInfoEnabled()) {
            log(Level.INFO, format(pattern, arguments), null);
        }
    }

    @Override
    public boolean isWarnEnabled() {
        return logger.isEnabledFor(Level.WARN);
    }

    @Override
    public void warn(String message) {
        log(Level.WARN, message, null);
    }

    @Override
    public void warn(String message, Throwable cause) {
        log(Level.WARN, message, cause);
    }

    @Override
    public void warn(String pattern, Object argument) {
        log(Level.WARN, format(pattern, argument), null);
    }

    @Override
    public void warn(String pattern, Object first, Object second) {
        log(Level.WARN, format(pattern, first, second), null);
    }

    @Override
    public void warn(String pattern, Object[] arguments) {
        log(Level.WARN, format(pattern, arguments), null);
    }

    @Override
    public boolean isErrorEnabled() {
        return logger.isEnabledFor(Level.ERROR);
    }

    @Override
    public void error(String message) {
        log(Level.ERROR, message, null);
    }

    @Override
    public void error(String message, Throwable cause) {
        log(Level.ERROR, message, cause);
    }

    @Override
    public void error(String pattern, Object argument) {
        log(Level.ERROR, format(pattern, argument), null);
    }

    @Override
    public void error(String pattern, Object first, Object second) {
        log(Level.ERROR, format(pattern, first, second), null);
    }

    @Override
    public void error(String pattern, Object[] arguments) {
        log(Level.ERROR, format(pattern, arguments), null);
    }
}
