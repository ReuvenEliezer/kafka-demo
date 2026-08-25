package com.reuven.kafka.demo.copy.exception;

/**
 * Redis was unreachable for a checkpoint read/write. Retried with backoff — checkpoint loss costs a
 * retransfer, never data loss (FR-032, FR-033).
 */
public class CheckpointUnavailableException extends TransientCopyException {

    public CheckpointUnavailableException(String message) {
        super(message);
    }

    public CheckpointUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
