package com.reuven.kafka.demo.copy.ingress;

import com.reuven.kafka.demo.copy.ingress.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the ingress's own exceptions to the status codes the provider needs to decide whether to
 * retry (FR-080): permanent (400/401/408) vs transient (503). Messages never echo the signature,
 * the secret, or a download URL (FR-080) — every message here is a static, generic description.
 */
@RestControllerAdvice
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class NotificationExceptionHandler {

    @ExceptionHandler(SignatureInvalidException.class)
    public ResponseEntity<ErrorResponse> handleSignatureInvalid(SignatureInvalidException e) {
        log.warn("Notification rejected: invalid signature");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(ErrorResponse.Code.SIGNATURE_INVALID, "Signature verification failed"));
    }

    @ExceptionHandler(TimestampStaleException.class)
    public ResponseEntity<ErrorResponse> handleTimestampStale(TimestampStaleException e) {
        log.warn("Notification rejected: signed timestamp outside the freshness window");
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                .body(new ErrorResponse(ErrorResponse.Code.TIMESTAMP_STALE, "Signed timestamp outside the freshness window"));
    }

    @ExceptionHandler(MalformedNotificationException.class)
    public ResponseEntity<ErrorResponse> handleMalformed(MalformedNotificationException e) {
        log.warn("Notification rejected: malformed body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorResponse.Code.MALFORMED_NOTIFICATION, "Notification body could not be processed"));
    }

    @ExceptionHandler(PublishFailedException.class)
    public ResponseEntity<ErrorResponse> handlePublishFailed(PublishFailedException e) {
        log.error("Notification publish failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(ErrorResponse.Code.PUBLISH_FAILED, "Failed to durably publish; please retry"));
    }
}
