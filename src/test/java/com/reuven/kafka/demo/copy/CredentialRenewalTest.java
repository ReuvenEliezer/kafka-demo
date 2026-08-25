package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.delivery.ChunkedUploader;
import com.reuven.kafka.demo.copy.delivery.UploadOutcome;
import com.reuven.kafka.demo.copy.delivery.UploadRequest;
import com.reuven.kafka.demo.copy.provider.ProviderClient;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import com.reuven.kafka.demo.copy.support.CopyIntegrationTestBase;
import com.reuven.kafka.demo.copy.support.CopyTestFixtures;
import com.reuven.kafka.demo.copy.support.FakeProviderServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * quickstart.md S12 — SC-019, SC-020. Margin set generously long (3s) against a deliberately
 * short-lived credential (4s) so renewal is forced within a few parts, without needing the test to
 * race real clock precision.
 */
class CredentialRenewalTest extends CopyIntegrationTestBase {

    private static final String BUCKET = "kafka-demo-events-test";
    private static final long MB = 1024L * 1024L;

    private static FakeProviderServer PROVIDER;

    @DynamicPropertySource
    static void providerAndMargin(DynamicPropertyRegistry registry) throws IOException {
        PROVIDER = new FakeProviderServer();
        registry.add("copy.provider.base-url", PROVIDER::baseUrl);
        registry.add("copy.provider.credential-renewal-margin", () -> "3s");
        // Direct ChunkedUploader calls bypass the claim protocol — disable the real background
        // DeliveryWorker so it cannot race this test for the same staged item (see
        // ResumableUploadIntegrationTest for the full explanation).
        registry.add("copy.delivery.worker-concurrency", () -> 0);
    }

    @BeforeAll
    static void createBucket() throws Exception {
        LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @Autowired
    private ChunkedUploader chunkedUploader;

    @Autowired
    private StagedItemRepository stagedItemRepository;

    @Autowired
    private ProviderClient providerClient;

    @Test
    @DisplayName("a transfer outliving its credential renews at a chunk boundary and completes without restarting (SC-019)")
    void transferOutlivingCredentialRenewsAndCompletes() {
        String recordingFileId = "credential-outlives-" + System.nanoTime();
        long payloadSize = 15 * MB; // 3 parts of 5 MB (test config)
        PROVIDER.registerRecording(recordingFileId, payloadSize);
        PROVIDER.setCredentialLifetime(recordingFileId, Duration.ofSeconds(4));
        // ~2s per part (640 chunks x 3ms) so remaining credential lifetime drops under the 3s margin
        // by the second or third part boundary, forcing at least one mid-transfer renewal.
        PROVIDER.setThrottle(recordingFileId, Duration.ofMillis(3));
        StagedItem item = persistStagedItem(recordingFileId, payloadSize);

        UploadOutcome outcome = chunkedUploader.upload(new UploadRequest(item, providerClient, payloadSize));

        assertThat(outcome.bytesUploaded()).isEqualTo(payloadSize);
        assertThat(PROVIDER.credentialMintCount(recordingFileId))
                .as("at least one renewal beyond the initial mint")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("a retry attempted after the original credential would have expired still succeeds (SC-020)")
    void retryAfterOriginalCredentialWouldHaveExpiredStillSucceeds() throws InterruptedException {
        String recordingFileId = "outlives-original-credential-" + System.nanoTime();
        long payloadSize = 6 * MB; // 2 parts of 5 MB
        PROVIDER.registerRecording(recordingFileId, payloadSize);
        PROVIDER.setCredentialLifetime(recordingFileId, Duration.ofSeconds(1));
        StagedItem item = persistStagedItem(recordingFileId, payloadSize);

        // Mimic "days later": the item holds only recordingFileId (FR-059), never a credential, so
        // nothing here depends on time having passed — but sleeping past what the *original*
        // notification-time credential would have allowed makes the point concrete.
        Thread.sleep(1500);

        UploadOutcome outcome = chunkedUploader.upload(new UploadRequest(item, providerClient, payloadSize));

        assertThat(outcome.bytesUploaded()).isEqualTo(payloadSize);
    }

    private StagedItem persistStagedItem(String recordingFileId, long declaredSize) {
        StagedItem item = CopyTestFixtures.stagedItemBuilder(recordingFileId)
                .destinationBucket(BUCKET)
                .destinationKey("recordings/" + recordingFileId)
                .declaredSizeBytes(declaredSize)
                .build();
        return stagedItemRepository.save(item);
    }
}
