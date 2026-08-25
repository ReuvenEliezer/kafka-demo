package com.reuven.kafka.demo.copy.exception;

/**
 * One of the three verification layers (per-part checksum, full-object checksum, size) failed after
 * {@code CompleteMultipartUpload} or {@code PutObject} (R14), or the stream ended short of the
 * declared size (FR-051). The item is never marked {@code DELIVERED} and no release signal becomes
 * reachable for it. Retried with backoff like any other transfer failure — corruption in transit is
 * not necessarily permanent — and only becomes terminal once attempts are exhausted (FR-019).
 */
public class IntegrityVerificationException extends TransientCopyException {

    public IntegrityVerificationException(String message) {
        super(message);
    }
}
