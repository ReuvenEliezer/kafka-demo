package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.checkpoint.CheckpointStore;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * quickstart.md S1 and S2 — SC-001, SC-002, SC-003. Drives {@link ChunkedUploader} directly (not
 * through {@code DeliveryWorker}) so a failure is observed as a thrown exception rather than an
 * async state transition, keeping the resume assertions precise.
 */
class ResumableUploadIntegrationTest extends CopyIntegrationTestBase {

    private static final String BUCKET = "kafka-demo-events-test";
    private static final long MB = 1024L * 1024L;
    private static final long PAYLOAD_SIZE = 48 * MB; // 5 MB parts (test config) -> 10 parts, last one 3 MB

    private static FakeProviderServer PROVIDER;

    @DynamicPropertySource
    static void providerBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        PROVIDER = new FakeProviderServer();
        registry.add("copy.provider.base-url", PROVIDER::baseUrl);
        // This test drives ChunkedUploader directly, bypassing the claim protocol entirely — the
        // real background DeliveryWorker threads are live in this context too (CopyIntegrationTestBase
        // activates the full staged strategy) and would otherwise race this test for the same staged
        // item, since the item never gets an exclusive claim from a direct upload() call.
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
    private CheckpointStore checkpointStore;

    @Autowired
    private StagedItemRepository stagedItemRepository;

    @Autowired
    private ProviderClient providerClient;

    @Autowired
    @Qualifier("deliveryS3Client")
    private S3Client deliveryS3Client;

    @Test
    @DisplayName("S1: interrupted after ~90% confirmed resumes on both legs and finalizes byte-identically (SC-001, SC-003)")
    void resumesAfterInterruptionOnBothLegs() {
        String recordingFileId = "resumable-" + System.nanoTime();
        PROVIDER.registerRecording(recordingFileId, PAYLOAD_SIZE);
        StagedItem item = persistStagedItem(recordingFileId);

        // Fail exactly after 9 of 10 parts (45 MB) — the provider connection drops mid-part-10.
        PROVIDER.setFailAfterBytes(recordingFileId, 45 * MB);

        assertThatThrownBy(() -> chunkedUploader.upload(new UploadRequest(item, providerClient, PAYLOAD_SIZE)));

        assertThat(PROVIDER.bytesServed(recordingFileId))
                .as("first attempt should have downloaded exactly the 45 MB before the induced failure")
                .isEqualTo(45 * MB);
        assertThat(checkpointStore.read(item.getDestinationBucket(), item.getDestinationKey()))
                .hasValueSatisfying(checkpoint -> assertThat(checkpoint.confirmedPrefixLength()).isEqualTo(9));

        // Retry: same call, same (now-persisted) checkpoint. No fault armed this time.
        UploadOutcome outcome = chunkedUploader.upload(new UploadRequest(item, providerClient, PAYLOAD_SIZE));

        assertThat(outcome.bytesUploaded()).isEqualTo(PAYLOAD_SIZE);
        assertThat(PROVIDER.bytesServed(recordingFileId))
                .as("cumulative download across both attempts should be ~100%% of the payload, not ~190%% — "
                        + "the download leg resumed from the derived position rather than restarting")
                .isEqualTo(PAYLOAD_SIZE);

        assertThat(checkpointStore.read(item.getDestinationBucket(), item.getDestinationKey()))
                .as("checkpoint deleted on finalization (FR-036)")
                .isEmpty();

        assertFinalizedObjectByteIdentical(recordingFileId, item.getDestinationBucket(), item.getDestinationKey());
    }

    @Test
    @DisplayName("S2: provider ignores Range — download leg repeats but the upload leg still only sends the missing parts (SC-002)")
    void resumesUploadLegEvenWhenProviderIgnoresRange() {
        String recordingFileId = "ignores-range-" + System.nanoTime();
        PROVIDER.registerRecording(recordingFileId, PAYLOAD_SIZE);
        PROVIDER.setIgnoreRange(recordingFileId, true);
        StagedItem item = persistStagedItem(recordingFileId);

        PROVIDER.setFailAfterBytes(recordingFileId, 45 * MB);
        assertThatThrownBy(() -> chunkedUploader.upload(new UploadRequest(item, providerClient, PAYLOAD_SIZE)));
        assertThat(PROVIDER.bytesServed(recordingFileId)).isEqualTo(45 * MB);

        UploadOutcome outcome = chunkedUploader.upload(new UploadRequest(item, providerClient, PAYLOAD_SIZE));

        assertThat(outcome.bytesUploaded()).isEqualTo(PAYLOAD_SIZE);
        assertThat(PROVIDER.bytesServed(recordingFileId))
                .as("ignored Range means the second attempt re-reads the full payload from byte 0: "
                        + "45 MB (first attempt) + 48 MB (second, full re-read) = 93 MB")
                .isEqualTo(45 * MB + PAYLOAD_SIZE);

        assertFinalizedObjectByteIdentical(recordingFileId, item.getDestinationBucket(), item.getDestinationKey());
    }

    private StagedItem persistStagedItem(String recordingFileId) {
        StagedItem item = CopyTestFixtures.stagedItemBuilder(recordingFileId)
                .destinationBucket(BUCKET)
                .destinationKey("recordings/" + recordingFileId)
                .build();
        return stagedItemRepository.save(item);
    }

    private void assertFinalizedObjectByteIdentical(String recordingFileId, String bucket, String key) {
        assertThat(deliveryS3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength())
                .isEqualTo(PAYLOAD_SIZE);

        long mismatchOffset = -1;
        long offset = 0;
        try (InputStream actual = deliveryS3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] buffer = new byte[8192];
            int n;
            outer:
            while ((n = actual.read(buffer)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buffer[i] != FakeProviderServer.byteAt(recordingFileId, offset + i)) {
                        mismatchOffset = offset + i;
                        break outer;
                    }
                }
                offset += n;
            }
        } catch (NoSuchKeyException e) {
            throw new AssertionError("finalized object not found at " + bucket + "/" + key, e);
        } catch (IOException e) {
            throw new AssertionError("failed reading finalized object", e);
        }

        assertThat(mismatchOffset).as("offset of first byte mismatch (-1 = none)").isEqualTo(-1);
        assertThat(offset).as("total bytes read back from S3").isEqualTo(PAYLOAD_SIZE);
    }
}
