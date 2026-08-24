package com.reuven.kafka.demo.services;

public class S3ArchiveUnavailableException extends RuntimeException {

    public S3ArchiveUnavailableException(int eventId, Throwable cause) {
        super("S3 archive unavailable for event id=" + eventId, cause);
    }

}
