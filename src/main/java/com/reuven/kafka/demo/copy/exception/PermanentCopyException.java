package com.reuven.kafka.demo.copy.exception;

/**
 * A failure that must not be retried — the delivery worker transitions the item straight to
 * {@code PERMANENTLY_FAILED} (FR-019) rather than spending its attempt budget.
 */
public abstract class PermanentCopyException extends CopyException {

    protected PermanentCopyException(String message) {
        super(message);
    }

    protected PermanentCopyException(String message, Throwable cause) {
        super(message, cause);
    }
}
