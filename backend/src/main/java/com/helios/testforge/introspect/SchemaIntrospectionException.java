package com.helios.testforge.introspect;

/** Raised when a target schema cannot be read or is unusable as a dataset source. */
public class SchemaIntrospectionException extends RuntimeException {

    public SchemaIntrospectionException(String message) {
        super(message);
    }

    public SchemaIntrospectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
