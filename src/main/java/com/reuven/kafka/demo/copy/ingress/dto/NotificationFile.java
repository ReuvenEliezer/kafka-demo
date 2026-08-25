package com.reuven.kafka.demo.copy.ingress.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One element of {@code payload.object.recording_files} (notification-ingress.openapi.yaml). One
 * published message per element (FR-077).
 */
public record NotificationFile(
        String id,
        @JsonProperty("file_type") String fileType,
        @JsonProperty("download_url") String downloadUrl,
        @JsonProperty("file_size") Long fileSize,
        @JsonProperty("recording_end") Instant recordingEnd
) {
}
