package com.reuven.kafka.demo.copy.ingress;

/** Verified, but the topic did not durably accept the messages (503). Transient — the provider must retry (FR-079, FR-080). */
public class PublishFailedException extends NotificationProcessingException {
    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
