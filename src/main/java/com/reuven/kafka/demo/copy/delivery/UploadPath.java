package com.reuven.kafka.demo.copy.delivery;

/**
 * The two ways a payload can reach the destination object store, selected by
 * {@link UploadPathSelector} against {@code copy.chunking.threshold} (FR-023, FR-024).
 */
public enum UploadPath {
    /** Below the threshold: one streaming {@code PutObject}, no checkpoint created at all. */
    SINGLE_REQUEST,
    /** At or above the threshold: sequential resumable multipart upload, checkpointed per chunk. */
    CHUNKED
}
