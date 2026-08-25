package com.reuven.kafka.demo.copy.checkpoint;

import java.util.Optional;

/**
 * Redis-backed transfer checkpoint store (contracts/checkpoint-store.md). Disposable by design:
 * every failure mode here costs a re-transfer, never a lost or corrupted object (FR-032, FR-033).
 */
public interface CheckpointStore {

    /** Issued immediately after {@code CreateMultipartUpload} succeeds, before the first part is sent. */
    void create(String bucket, String key, String uploadId, long chunkSize, int chunkCount, long totalSize);

    /**
     * Atomically writes the confirmation and refreshes the sliding TTL in one round trip
     * (research.md R2). Returns {@code false} when the entry no longer exists — the caller must
     * abandon the attempt and restart rather than continue against a checkpoint that vanished.
     */
    boolean confirm(String bucket, String key, ChunkConfirmation confirmation);

    /** Absent key means restart the transfer — never that it completed (FR-032). */
    Optional<TransferCheckpoint> read(String bucket, String key);

    /** Issued on finalization (FR-036) and when a checkpoint is found stale at S3 (FR-034). */
    void delete(String bucket, String key);
}
