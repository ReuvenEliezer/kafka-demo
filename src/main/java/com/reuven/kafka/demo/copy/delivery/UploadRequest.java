package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.provider.ProviderClient;
import com.reuven.kafka.demo.copy.staging.StagedItem;

/**
 * Everything an {@link ObjectUploader} needs to move one item's bytes from the provider to the
 * destination. The destination {@code S3Client} is a constructor-injected dependency of the
 * uploader bean itself, not carried here.
 *
 * @param item                the staged item; its stable {@code recordingFileId} is what credentials
 *                             and downloads are keyed by, never a URL or credential from the message
 * @param providerClient      used to mint credentials and open the resumable download
 * @param effectiveSizeBytes  {@code coalesce(resolvedSizeBytes, declaredSizeBytes)} — the size the
 *                             chunk planner and single-request path both work from
 */
public record UploadRequest(
        StagedItem item,
        ProviderClient providerClient,
        long effectiveSizeBytes
) {
}
