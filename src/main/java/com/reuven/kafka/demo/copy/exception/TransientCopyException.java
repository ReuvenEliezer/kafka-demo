package com.reuven.kafka.demo.copy.exception;

/**
 * A failure the delivery worker should retry with backoff (FR-018) rather than fail permanently.
 */
public abstract class TransientCopyException extends CopyException {

    protected TransientCopyException(String message) {
        super(message);
    }

    protected TransientCopyException(String message, Throwable cause) {
        super(message, cause);
    }
}
