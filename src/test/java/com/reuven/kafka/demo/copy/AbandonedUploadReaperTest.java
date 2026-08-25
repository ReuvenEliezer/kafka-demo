package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.cleanup.AbandonedUploadReaper;
import com.reuven.kafka.demo.copy.support.CopyIntegrationTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * quickstart.md S15 — SC-014, SC-015, FR-056. The retention window is shrunk to a few seconds for
 * this test class (with max-attempts/max-backoff shrunk too, so the startup validation V2/V3
 * ordering — {@code maxRetrySpan < checkpoint.expiry < abandoned-upload-retention} — still holds),
 * so "past retention" is produced by actually waiting it out rather than mocking the clock.
 */
class AbandonedUploadReaperTest extends CopyIntegrationTestBase {

    private static final String BUCKET = "kafka-demo-events-test";

    @DynamicPropertySource
    static void shortRetention(DynamicPropertyRegistry registry) {
        registry.add("copy.delivery.max-attempts", () -> 1);
        registry.add("copy.delivery.max-backoff", () -> "100ms");
        registry.add("copy.checkpoint.expiry", () -> "1s");
        registry.add("copy.cleanup.abandoned-upload-retention", () -> "3s");
    }

    @BeforeAll
    static void createBucket() throws Exception {
        LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET);
    }

    @Autowired
    private AbandonedUploadReaper reaper;

    @Autowired
    @Qualifier("deliveryS3Client")
    private S3Client deliveryS3Client;

    @Test
    @DisplayName("an upload past the retention window is aborted; one still within it is preserved")
    void abortsPastRetentionPreservesWithin() throws InterruptedException {
        String keyOld = "recordings/abandoned-old-" + System.nanoTime();
        CreateMultipartUploadResponse oldUpload = deliveryS3Client.createMultipartUpload(b -> b.bucket(BUCKET).key(keyOld));

        Thread.sleep(3500); // exceed the 3s retention window

        String keyRecent = "recordings/abandoned-recent-" + System.nanoTime();
        CreateMultipartUploadResponse recentUpload = deliveryS3Client.createMultipartUpload(b -> b.bucket(BUCKET).key(keyRecent));

        reaper.scan();

        assertThat(uploadStillExists(keyOld, oldUpload.uploadId()))
                .as("past the retention window: aborted (FR-055)")
                .isFalse();
        assertThat(uploadStillExists(keyRecent, recentUpload.uploadId()))
                .as("still within the retention window: preserved so resumption stays possible (FR-056)")
                .isTrue();

        deliveryS3Client.abortMultipartUpload(b -> b.bucket(BUCKET).key(keyRecent).uploadId(recentUpload.uploadId()));
    }

    private boolean uploadStillExists(String key, String uploadId) {
        try {
            deliveryS3Client.listParts(b -> b.bucket(BUCKET).key(key).uploadId(uploadId));
            return true;
        } catch (NoSuchUploadException e) {
            return false;
        }
    }
}
