package com.reuven.kafka.demo.copy.delivery;

import org.springframework.util.unit.DataSize;

/**
 * Chunk-size derivation (research.md R4, FR-026):
 *
 * <pre>
 * partSize = clamp(max(basePartSize, ceilToMiB(payloadSize / MAX_PARTS)), MIN_PART_SIZE, MAX_PART_SIZE)
 * </pre>
 *
 * <p>The {@code ceilToMiB(payloadSize / MAX_PARTS)} term is what keeps very large payloads under the
 * S3 part-count ceiling — at 5 TiB it yields parts in the hundreds of MB. The 16 MiB default base
 * (rather than a base equal to the chunking threshold) means a payload just over the threshold still
 * splits into several parts, so resumability is real at the bottom of the chunked range.
 *
 * @param partSize  bytes per part; fixed for the life of the upload
 * @param partCount {@code ceil(payloadSize / partSize)}; only the final part may fall below the minimum
 */
public record ChunkPlan(long partSize, int partCount) {

    public static final int MAX_PARTS = 10_000;
    public static final long MIN_PART_SIZE = DataSize.ofMegabytes(5).toBytes();
    public static final long MAX_PART_SIZE = DataSize.ofGigabytes(5).toBytes();

    public static ChunkPlan forPayload(long payloadSize, long basePartSize) {
        if (payloadSize <= 0) {
            throw new IllegalArgumentException("payloadSize must be positive, was " + payloadSize);
        }
        if (basePartSize > MAX_PART_SIZE) {
            throw new IllegalArgumentException(
                    "copy.chunking.base-part-size (%d bytes) exceeds the S3 maximum part size (%d bytes)"
                            .formatted(basePartSize, MAX_PART_SIZE));
        }

        long sizeDrivenPartSize = ceilToMiB(ceilDiv(payloadSize, MAX_PARTS));
        long partSize = Math.max(basePartSize, sizeDrivenPartSize);
        if (partSize > MAX_PART_SIZE) {
            // A misconfigured base, or a payload far beyond the 5 TiB environment assumption
            // (data-model.md), is a real error to surface rather than silently clamp away.
            throw new IllegalArgumentException(
                    "Derived part size (%d bytes) for a %d-byte payload exceeds the S3 maximum part size (%d bytes)"
                            .formatted(partSize, payloadSize, MAX_PART_SIZE));
        }
        partSize = Math.max(partSize, MIN_PART_SIZE);

        int partCount = (int) ceilDiv(payloadSize, partSize);
        return new ChunkPlan(partSize, partCount);
    }

    private static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1) / denominator;
    }

    private static long ceilToMiB(long bytes) {
        long mib = DataSize.ofMegabytes(1).toBytes();
        return ceilDiv(bytes, mib) * mib;
    }
}
