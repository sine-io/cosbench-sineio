package com.intel.cosbench.config.runtime;

/** A readable configuration document violates the versioned service contract. */
public class ConfigValidationException extends ConfigLoadException {

    private static final long serialVersionUID = 1L;

    public ConfigValidationException(ConfigProblemCode code, String message) {
        super(code, message);
    }

    public ConfigValidationException(ConfigProblemCode code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
