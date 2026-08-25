package com.reuven.kafka.demo.copy.exception;

/**
 * The provider reports the recording no longer exists (deleted, expired). Retrying cannot help, so
 * the item fails permanently rather than spending its attempt budget.
 */
public class RecordingNotFoundException extends PermanentCopyException {

    public RecordingNotFoundException(String message) {
        super(message);
    }
}
