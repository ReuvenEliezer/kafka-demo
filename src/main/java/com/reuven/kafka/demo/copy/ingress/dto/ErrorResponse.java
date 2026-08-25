package com.reuven.kafka.demo.copy.ingress.dto;

public record ErrorResponse(
        Code code,
        String message
) {

    /** Never echoes the signature, the secret, or any download URL. */
    public enum Code {
        SIGNATURE_INVALID,
        TIMESTAMP_STALE,
        MALFORMED_NOTIFICATION,
        PUBLISH_FAILED
    }
}
