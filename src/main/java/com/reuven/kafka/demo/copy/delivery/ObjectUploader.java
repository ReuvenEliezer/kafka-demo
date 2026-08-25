package com.reuven.kafka.demo.copy.delivery;

/**
 * Strategy interface for the two upload paths (FR-023, FR-024). Enum-keyed auto-registration
 * (research.md R21) rather than a branch — {@link UploaderRegistryConfig} builds the
 * {@code UploadPath -> ObjectUploader} map and fails startup on a duplicate key.
 */
public interface ObjectUploader {

    UploadPath uploadPath();

    UploadOutcome upload(UploadRequest request);
}
