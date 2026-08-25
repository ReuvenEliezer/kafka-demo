package com.reuven.kafka.demo.copy.support;

import com.reuven.kafka.demo.copy.message.RecordingCopyMessage;
import com.reuven.kafka.demo.copy.staging.StagedItem;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-test builders for staged items and copy messages. No shared mutable state — every call
 * produces a fresh, independently-mutable object so tests cannot leak state into one another.
 */
public final class CopyTestFixtures {

    private CopyTestFixtures() {
    }

    public static StagedItem.StagedItemBuilder stagedItemBuilder(String recordingFileId) {
        Instant now = Instant.now();
        String sessionId = "session-" + recordingFileId;
        return StagedItem.builder()
                .recordingFileId(recordingFileId)
                .sessionId(sessionId)
                .providerAccountId("account-1")
                .providerEventId("event-" + UUID.randomUUID())
                .destinationBucket("test-bucket")
                .destinationKey("recordings/account-1/%s/%s".formatted(sessionId, recordingFileId))
                .declaredSizeBytes(1024L)
                .contentType("application/octet-stream")
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now);
    }

    public static RecordingCopyMessage recordingCopyMessage(String recordingFileId, String downloadUrl, long declaredSizeBytes) {
        return new RecordingCopyMessage(
                recordingFileId,
                "session-" + recordingFileId,
                "account-1",
                "MP4",
                downloadUrl,
                declaredSizeBytes,
                "video/mp4",
                Instant.now());
    }
}
