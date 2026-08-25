package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.exception.DeclaredSizeUnderstatedException;
import com.reuven.kafka.demo.copy.exception.IntegrityVerificationException;
import com.reuven.kafka.demo.copy.exception.ProviderUnavailableException;
import com.reuven.kafka.demo.copy.exception.S3DeliveryUnavailableException;
import com.reuven.kafka.demo.copy.provider.ProviderClient;
import com.reuven.kafka.demo.copy.provider.ProviderCredential;
import com.reuven.kafka.demo.copy.provider.ProviderDownload;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.util.zip.CRC32C;

/**
 * Below-threshold payloads take one streaming {@code PutObject} — no checkpoint, no multipart
 * bookkeeping at all (FR-023, FR-024, invariant I4). Registered under {@link UploadPath#SINGLE_REQUEST}.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class SingleRequestUploader implements ObjectUploader {

    private final S3Client deliveryS3Client;
    private final SizeResolver sizeResolver;

    public SingleRequestUploader(@Qualifier("deliveryS3Client") S3Client deliveryS3Client,
                                  SizeResolver sizeResolver) {
        this.deliveryS3Client = deliveryS3Client;
        this.sizeResolver = sizeResolver;
    }

    @Override
    public UploadPath uploadPath() {
        return UploadPath.SINGLE_REQUEST;
    }

    @Override
    @CircuitBreaker(name = "s3Delivery")
    public UploadOutcome upload(UploadRequest request) {
        StagedItem item = request.item();
        ProviderClient providerClient = request.providerClient();
        long effectiveSize = request.effectiveSizeBytes();

        ProviderCredential credential = providerClient.mintDownloadCredential(item.getRecordingFileId());

        try (ProviderDownload download = providerClient.openDownload(item.getRecordingFileId(), credential, 0)) {
            CRC32C localChecksum = new CRC32C();
            BoundedInputStream bounded = new BoundedInputStream(download.body(), effectiveSize);
            CountingChecksumInputStream measured = new CountingChecksumInputStream(bounded, localChecksum);

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(item.getDestinationBucket())
                    .key(item.getDestinationKey())
                    .contentType(item.getContentType())
                    .checksumAlgorithm(ChecksumAlgorithm.CRC32_C)
                    .build();

            PutObjectResponse response;
            try {
                response = deliveryS3Client.putObject(putRequest, RequestBody.fromContentProvider(() -> measured, effectiveSize, "application/octet-stream"));
            } catch (SdkException e) {
                throw new S3DeliveryUnavailableException(
                        "PutObject failed for %s/%s".formatted(item.getDestinationBucket(), item.getDestinationKey()), e);
            }

            if (measured.bytesRead() != effectiveSize) {
                throw new IntegrityVerificationException(
                        "Provider stream ended short for %s: expected %d bytes, read %d"
                                .formatted(item.getRecordingFileId(), effectiveSize, measured.bytesRead()));
            }

            // FR-050: the declared/resolved size may simply be wrong. Probing the *unbounded*
            // underlying stream (not the bounded wrapper the SDK just finished draining) for one
            // more byte is how a size that understated the true payload is caught, after the fact
            // rather than mid-request — a single PutObject can't be redirected to multipart once
            // it has started.
            if (download.body().read() != -1) {
                deleteQuietly(item.getDestinationBucket(), item.getDestinationKey());
                long correctedSize = sizeResolver.lookupAndPersist(item, providerClient);
                throw new DeclaredSizeUnderstatedException(
                        "Declared size %d for %s understated the true payload (>= %d bytes); corrected size persisted, retry will chunk"
                                .formatted(effectiveSize, item.getRecordingFileId(), correctedSize));
            }

            return new UploadOutcome(response.checksumCRC32C(), effectiveSize);
        } catch (IOException e) {
            throw new ProviderUnavailableException("Failed closing provider download for " + item.getRecordingFileId(), e);
        }
    }

    private void deleteQuietly(String bucket, String key) {
        try {
            deliveryS3Client.deleteObject(b -> b.bucket(bucket).key(key));
        } catch (SdkException e) {
            log.warn("Failed to delete truncated object {}/{}: {}", bucket, key, e.getMessage());
        }
    }
}
