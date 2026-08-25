package com.reuven.kafka.demo.copy.checkpoint;

/**
 * Evidence that one chunk is stored: its ordinal <b>and</b> the acknowledgement token the object
 * store returned for it (FR-028). An ordinal alone is not a usable confirmation —
 * {@code CompleteMultipartUpload} requires {@code (partNumber, eTag)} pairs, so a checkpoint
 * recording only ordinals would resume correctly and then be unable to finalize.
 */
public record ChunkConfirmation(
        int partNumber,
        String etag,
        String crc32c
) {
}
