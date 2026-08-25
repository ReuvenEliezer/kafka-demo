package com.reuven.kafka.demo.copy.exception;

/**
 * {@code SingleRequestUploader} found more bytes than the resolved size declared. Transient: by the
 * time this is thrown, a corrected size has already been persisted to {@code resolved_size_bytes},
 * so the retry this triggers naturally routes through {@code UploadPathSelector} to
 * {@code CHUNKED} instead — no special-cased orchestration needed in {@code DeliveryWorker} (FR-050).
 */
public class DeclaredSizeUnderstatedException extends TransientCopyException {

    public DeclaredSizeUnderstatedException(String message) {
        super(message);
    }
}
