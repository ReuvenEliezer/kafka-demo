package com.reuven.kafka.demo.copy.provider;

import java.io.Closeable;
import java.io.InputStream;

/**
 * The resumable read leg's response (contracts/provider-client.md §3). {@code body} is unbuffered
 * and must never be materialised in full (FR-021, FR-022).
 *
 * @param firstByteOffset 0 when the provider ignored the range header
 * @param rangeHonoured   {@code true} for a {@code 206}; {@code false} for a {@code 200}, in which
 *                         case the caller must read and discard the first {@code firstByteOffset}
 *                         bytes itself (FR-045)
 */
public record ProviderDownload(
        InputStream body,
        long firstByteOffset,
        long totalSize,
        boolean rangeHonoured
) implements Closeable {

    @Override
    public void close() throws java.io.IOException {
        body.close();
    }
}
