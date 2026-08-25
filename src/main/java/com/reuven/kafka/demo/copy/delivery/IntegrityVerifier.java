package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.exception.IntegrityVerificationException;
import com.reuven.kafka.demo.copy.exception.S3DeliveryUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * The gate between "the uploader returned" and {@code DELIVERED} (research.md R14). Evidence comes
 * from the object store, not from the uploader's own belief that it finished — the spec calls a
 * premature release "the one unrecoverable failure in the feature", so this is deliberately
 * paranoid rather than trusting.
 *
 * <p>The hard gate here is size (layer 3): {@code HeadObject}'s content length must match what the
 * transfer declared. A full-object checksum, when {@code CompleteMultipartUpload} happens to return
 * one, is logged as corroborating evidence but is not required — see {@code ChunkedUploader}'s class
 * javadoc for why relying on S3-side checksums proved unworkable against real LocalStack. Byte-level
 * correctness (SC-007) is what this repository's own integration tests verify directly, by comparing
 * the finalized object against the source.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class IntegrityVerifier {

    private final S3Client deliveryS3Client;

    public IntegrityVerifier(@Qualifier("deliveryS3Client") S3Client deliveryS3Client) {
        this.deliveryS3Client = deliveryS3Client;
    }

    public void verify(String bucket, String key, long expectedSizeBytes, UploadOutcome outcome) {
        if (outcome.fullObjectChecksumCrc32c() == null || outcome.fullObjectChecksumCrc32c().isBlank()) {
            log.debug("No full-object checksum available for {}/{}; verifying by size only", bucket, key);
        }

        HeadObjectResponse head;
        try {
            head = deliveryS3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new S3DeliveryUnavailableException("HeadObject failed verifying %s/%s".formatted(bucket, key), e);
        }

        if (head.contentLength() == null || head.contentLength() != expectedSizeBytes) {
            throw new IntegrityVerificationException(
                    "Finalized object size mismatch for %s/%s: expected %d bytes, HeadObject reports %s"
                            .formatted(bucket, key, expectedSizeBytes, head.contentLength()));
        }
    }
}
