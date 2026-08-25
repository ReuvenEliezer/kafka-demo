package com.reuven.kafka.demo.copy.exception;

/**
 * The destination object store rejected or failed to complete a delivery call (the {@code s3Delivery}
 * breaker, FR-020). Retried with backoff; never trips the inline strategy's {@code s3Upload} breaker.
 */
public class S3DeliveryUnavailableException extends TransientCopyException {

    public S3DeliveryUnavailableException(String message) {
        super(message);
    }

    public S3DeliveryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
