package com.intel.cosbench.config.runtime;

/** Stable diagnostic categories emitted by configuration loading and migration. */
public enum ConfigProblemCode {
    DEFAULT_FILE_MISSING,
    EXPLICIT_FILE_MISSING,
    EMPTY_DOCUMENT,
    MALFORMED_YAML,
    UNSUPPORTED_SCHEMA,
    REQUIRED_FIELD_MISSING,
    UNKNOWN_KEY,
    INVALID_TYPE,
    INVALID_VALUE,
    VALUE_OUT_OF_RANGE,
    SECRET_UNRESOLVED,
    LEGACY_UNMAPPED,
    DESTINATION_EXISTS,
    IO_ERROR
}
