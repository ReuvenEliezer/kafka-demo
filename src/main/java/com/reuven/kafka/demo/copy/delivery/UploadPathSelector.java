package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Routes on {@code copy.chunking.threshold} — one threshold governs both chunking and
 * checkpointing (FR-023, FR-024), so there is no band where a payload pays for chunk bookkeeping
 * without gaining resumability.
 *
 * <p>Reads the threshold through {@link CopyProperties} on every call rather than caching it at
 * construction, so a restart with a changed value governs subsequent decisions without needing a
 * separate reload path.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class UploadPathSelector {

    private final CopyProperties properties;

    public UploadPath select(long effectiveSizeBytes) {
        return effectiveSizeBytes >= properties.chunking().threshold().toBytes()
                ? UploadPath.CHUNKED
                : UploadPath.SINGLE_REQUEST;
    }
}
