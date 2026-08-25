package com.reuven.kafka.demo.copy.provider;

import java.time.Instant;

/**
 * Result of the fallback size lookup, used only when the message's declared size is absent or
 * implausible (FR-048). Called at most once per staged item across all retries (FR-049).
 */
public record RecordingMetadata(
        long sizeBytes,
        String contentType,
        Instant lastModified
) {
}
