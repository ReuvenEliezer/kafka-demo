package com.reuven.kafka.demo.copy.exception;

/**
 * Thrown from {@code CopyProperties}' compact constructor when {@code copy.chunking.threshold}
 * exceeds the S3 single-request maximum of 5 GiB (V1, FR-025).
 */
public class InvalidChunkingThresholdException extends CopyException {

    public InvalidChunkingThresholdException(String message) {
        super(message);
    }
}
