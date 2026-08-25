package com.reuven.kafka.demo.copy.exception;

/**
 * The provider was unreachable or answered with a transient status (timeout, 5xx, throttling).
 * Retried with backoff (FR-018).
 */
public class ProviderUnavailableException extends TransientCopyException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
