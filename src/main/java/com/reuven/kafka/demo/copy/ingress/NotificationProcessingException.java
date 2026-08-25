package com.reuven.kafka.demo.copy.ingress;

/** Base of the notification ingress's own exception hierarchy, mapped to HTTP status by {@link NotificationExceptionHandler}. */
public abstract class NotificationProcessingException extends RuntimeException {

    protected NotificationProcessingException(String message) {
        super(message);
    }

    protected NotificationProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
