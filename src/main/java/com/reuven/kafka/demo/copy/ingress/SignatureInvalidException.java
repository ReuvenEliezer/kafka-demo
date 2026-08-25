package com.reuven.kafka.demo.copy.ingress;

/** Missing, malformed, or incorrect signature (401). Permanent — the provider should not retry (FR-080). */
public class SignatureInvalidException extends NotificationProcessingException {
    public SignatureInvalidException(String message) {
        super(message);
    }
}
