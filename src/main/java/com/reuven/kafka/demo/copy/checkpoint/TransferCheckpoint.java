package com.reuven.kafka.demo.copy.checkpoint;

import java.time.Instant;
import java.util.Map;

/**
 * Resumption state for one in-progress chunked upload (data-model.md §2.1). Disposable by design —
 * absence means "restart", never "done" (FR-032, FR-033).
 *
 * @param confirmedChunks keyed by 1-based part number
 */
public record TransferCheckpoint(
        String uploadId,
        long chunkSize,
        int chunkCount,
        long totalSize,
        Instant createdAt,
        Map<Integer, ChunkConfirmation> confirmedChunks
) {

    /**
     * The largest {@code k} such that parts {@code 1..k} are all confirmed — the <b>contiguous
     * prefix</b>, not the field count (§2.3). Sequential transfer (FR-042) should make gaps
     * impossible; computing the prefix means a gap costs a re-transfer, never a corrupt object.
     */
    public int confirmedPrefixLength() {
        int k = 0;
        while (confirmedChunks.containsKey(k + 1)) {
            k++;
        }
        return k;
    }

    /** Never stored (FR-043) — derived fresh every time so it cannot drift from the chunk record. */
    public long resumeBytePosition() {
        return (long) confirmedPrefixLength() * chunkSize;
    }

    public int nextPartNumber() {
        return confirmedPrefixLength() + 1;
    }
}
