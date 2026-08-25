package com.reuven.kafka.demo.copy.ingress;

/**
 * Signed timestamp outside the freshness window (408). Distinguished from 401 so genuine clock skew
 * is diagnosable separately from a forged signature (FR-072).
 */
public class TimestampStaleException extends NotificationProcessingException {
    public TimestampStaleException(String message) {
        super(message);
    }
}
