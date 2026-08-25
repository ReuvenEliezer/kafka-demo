package com.reuven.kafka.demo.copy.delivery;

/**
 * What an {@link ObjectUploader} proves about a completed transfer — the evidence
 * {@code IntegrityVerifier} checks before an item may reach {@code DELIVERED} (research.md R14).
 *
 * @param fullObjectChecksumCrc32c base64 CRC32C over the assembled object, from S3 for a chunked
 *                                 upload's {@code CompleteMultipartUpload} or accumulated while
 *                                 streaming for a single-request {@code PutObject}
 * @param bytesUploaded            bytes actually written to the destination
 */
public record UploadOutcome(
        String fullObjectChecksumCrc32c,
        long bytesUploaded
) {
}
