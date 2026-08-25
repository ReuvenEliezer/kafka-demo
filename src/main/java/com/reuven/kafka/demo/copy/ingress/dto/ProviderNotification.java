package com.reuven.kafka.demo.copy.ingress.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A {@code recording.completed} notification (notification-ingress.openapi.yaml). {@code payload.object.uuid}
 * is the session identifier — grouping only, never sufficient as a destination key (FR-052).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderNotification(
        String event,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_ts") long eventTs,
        Payload payload
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            @JsonProperty("account_id") String accountId,
            RecordingObject object
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecordingObject(
            String uuid,
            String topic,
            @JsonProperty("recording_files") List<NotificationFile> recordingFiles
    ) {
    }
}
