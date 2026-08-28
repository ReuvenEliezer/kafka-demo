package com.reuven.kafka.demo.copy.ingress;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.reuven.kafka.demo.copy.ingress.dto.AcceptedResponse;
import com.reuven.kafka.demo.copy.ingress.dto.ProviderNotification;
import com.reuven.kafka.demo.copy.ingress.dto.UrlValidationRequest;
import com.reuven.kafka.demo.copy.ingress.dto.UrlValidationResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Verify, publish, respond — and nothing else (FR-081). No copying, no staging writes, no provider
 * callbacks inline (FR-074). The response is written only after {@link NotificationPublisher}'s
 * transaction has committed, so a 200 is a genuine promise that the topic durably accepted every
 * message (FR-079).
 */
@RestController
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class ProviderNotificationController {

    private static final String URL_VALIDATION_EVENT = "endpoint.url_validation";
    private static final String RECORDING_COMPLETED_EVENT = "recording.completed";

    private final NotificationSignatureVerifier signatureVerifier;
    private final NotificationPublisher publisher;
    private final ObjectMapper objectMapper;

    public ProviderNotificationController(NotificationSignatureVerifier signatureVerifier,
                                           NotificationPublisher publisher,
                                           ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("${copy.notification.path}")
    public Object receiveNotification(@RequestHeader(value = "X-Provider-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Provider-Request-Timestamp", required = false) String timestamp,
                                       HttpServletRequest request) {
        byte[] rawBody = rawBody(request);

        NotificationSignatureVerifier.VerificationResult result = signatureVerifier.verify(signature, timestamp, rawBody);
        if (result == NotificationSignatureVerifier.VerificationResult.INVALID_SIGNATURE) {
            throw new SignatureInvalidException("Signature verification failed");
        }
        if (result == NotificationSignatureVerifier.VerificationResult.STALE_TIMESTAMP) {
            throw new TimestampStaleException("Signed timestamp outside the freshness window");
        }

        JsonNode root = parseJson(rawBody);
        String event = root.path("event").asText(null);

        if (URL_VALIDATION_EVENT.equals(event)) {
            return handleUrlValidation(root);
        }
        if (RECORDING_COMPLETED_EVENT.equals(event)) {
            return handleRecordingCompleted(root);
        }
        throw new MalformedNotificationException("Unrecognised or missing event type");
    }

    private UrlValidationResponse handleUrlValidation(JsonNode root) {
        UrlValidationRequest validationRequest = convert(root, UrlValidationRequest.class);
        if (validationRequest.payload() == null || validationRequest.payload().plainToken() == null) {
            throw new MalformedNotificationException("endpoint.url_validation missing payload.plainToken");
        }
        String plainToken = validationRequest.payload().plainToken();
        return new UrlValidationResponse(plainToken, signatureVerifier.computeHex(plainToken));
    }

    private AcceptedResponse handleRecordingCompleted(JsonNode root) {
        ProviderNotification notification = convert(root, ProviderNotification.class);
        validate(notification);

        int published;
        try {
            published = publisher.publish(notification);
        } catch (Exception e) {
            throw new PublishFailedException("Failed to durably publish notification messages", e);
        }

        return new AcceptedResponse(true, published);
    }

    private static void validate(ProviderNotification notification) {
        if (notification.eventId() == null
                || notification.payload() == null
                || notification.payload().accountId() == null
                || notification.payload().object() == null
                || notification.payload().object().uuid() == null
                || notification.payload().object().recordingFiles() == null
                || notification.payload().object().recordingFiles().isEmpty()) {
            throw new MalformedNotificationException("recording.completed notification missing required fields");
        }
        for (var file : notification.payload().object().recordingFiles()) {
            if (file.id() == null || file.downloadUrl() == null) {
                throw new MalformedNotificationException("recording_files entry missing id or download_url");
            }
        }
    }

    private <T> T convert(JsonNode root, Class<T> type) {
        try {
            return objectMapper.treeToValue(root, type);
        } catch (JacksonException e) {
            throw new MalformedNotificationException("Notification body did not match the expected shape");
        }
    }

    private JsonNode parseJson(byte[] rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new MalformedNotificationException("Notification body is not valid JSON");
        }
    }

    private static byte[] rawBody(HttpServletRequest request) {
        ContentCachingRequestWrapper wrapper = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if (wrapper != null) {
            return wrapper.getContentAsByteArray();
        }
        try {
            return request.getInputStream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
