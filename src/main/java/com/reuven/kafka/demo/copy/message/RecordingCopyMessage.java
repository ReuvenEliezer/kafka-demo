package com.reuven.kafka.demo.copy.message;

import java.time.Instant;

/**
 * One message per recording <b>file</b>, never per notification (FR-077). Carries a reference,
 * never bytes — no download credential is present; one captured at notification time would be
 * expired by the time a multi-day retry span elapsed (FR-059, FR-060, SC-020).
 *
 * <p>Additive-only compatibility: a consumer must ignore unknown fields (contracts/recording-copy-message.md).
 */
public record RecordingCopyMessage(
        String recordingFileId,
        String sessionId,
        String providerAccountId,
        String fileType,
        String downloadUrl,
        Long declaredSizeBytes,
        String contentType,
        Instant recordingEndedAt
) {
}
