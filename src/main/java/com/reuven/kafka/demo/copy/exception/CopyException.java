package com.reuven.kafka.demo.copy.exception;

/**
 * Base of the staged-copy feature's exception hierarchy. Domain code throws a specific subtype,
 * never a bare {@link RuntimeException} or {@link IllegalStateException}.
 */
public abstract class CopyException extends RuntimeException {

    protected CopyException(String message) {
        super(message);
    }

    protected CopyException(String message, Throwable cause) {
        super(message, cause);
    }
}
