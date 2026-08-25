package com.reuven.kafka.demo.copy.exception;

/**
 * Thrown from {@code CopyProperties}' compact constructor when the ordering
 * {@code maxRetrySpan < copy.checkpoint.expiry < copy.cleanup.abandoned-upload-retention} does not
 * hold (V2, V3, FR-039, FR-040).
 */
public class InvalidCheckpointExpiryException extends CopyException {

    public InvalidCheckpointExpiryException(String message) {
        super(message);
    }
}
