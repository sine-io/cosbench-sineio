package com.intel.cosbench.config.runtime;

import com.intel.cosbench.config.ConfigException;

/** A configuration file could not be read before semantic validation completed. */
public class ConfigLoadException extends ConfigException {

    private static final long serialVersionUID = 1L;
    private final ConfigProblemCode code;

    public ConfigLoadException(ConfigProblemCode code, String message) {
        super(message);
        this.code = code;
    }

    public ConfigLoadException(ConfigProblemCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ConfigProblemCode code() {
        return code;
    }
}
