package com.reuven.kafka.demo.copy.exception;

/**
 * Two {@code ObjectUploader} beans registered under the same {@code UploadPath} key. Fails startup —
 * a silent duplicate would make the losing uploader unreachable rather than raising an error.
 */
public class DuplicateUploaderRegistrationException extends CopyException {

    public DuplicateUploaderRegistrationException(String message) {
        super(message);
    }
}
