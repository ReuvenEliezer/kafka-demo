package com.reuven.kafka.demo.copy.ingress;

/** Body unparseable, or required fields absent (400). Permanently unprocessable (FR-080). */
public class MalformedNotificationException extends NotificationProcessingException {
    public MalformedNotificationException(String message) {
        super(message);
    }
}
