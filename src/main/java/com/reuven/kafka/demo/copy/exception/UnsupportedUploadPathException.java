package com.reuven.kafka.demo.copy.exception;

/**
 * No {@code ObjectUploader} is registered for the {@code UploadPath} a delivery resolved to. Cannot
 * happen once every story has landed — both paths are always registered by then — so this is a
 * defensive guard for the incremental-delivery window, not an expected runtime condition.
 */
public class UnsupportedUploadPathException extends PermanentCopyException {

    public UnsupportedUploadPathException(String message) {
        super(message);
    }
}
