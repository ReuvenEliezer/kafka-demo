package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.staging.DeliveryState;
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
import static org.awaitility.Awaitility.await;

/**
 * quickstart.md S6 (size half) — SC-010. Drives the real background {@code DeliveryWorker} so
 * {@code SizeResolver} is exercised exactly as production wires it.
 */
class SizeResolutionTest extends CopyIntegrationTestBase {

    private static final String BUCKET = "kafka-demo-events-test";
    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;

    private static FakeProviderServer PROVIDER;

    @DynamicPropertySource
    static void providerBaseUrl(DynamicPropertyRegistry registry) throws IOException {
        PROVIDER = new FakeProviderServer();
        registry.add("copy.provider.base-url", PROVIDER::baseUrl);
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
    private StagedItemRepository stagedItemRepository;

    @Test
    @DisplayName("declared size present and plausible: zero metadata calls (SC-010)")
    void plausibleDeclaredSizeSkipsMetadataLookup() {
        String recordingFileId = "plausible-" + System.nanoTime();
        PROVIDER.registerRecording(recordingFileId, 2 * KB);
        StagedItem item = persist(recordingFileId, 2 * KB);

        awaitDelivered(item);

        assertThat(PROVIDER.metadataCallCount(recordingFileId)).isZero();
    }

    @Test
    @DisplayName("declared size absent: exactly one metadata lookup, persisted so a retry never repeats it")
    void absentDeclaredSizeLooksUpOnceAndPersists() {
        String recordingFileId = "absent-size-" + System.nanoTime();
        long actualSize = 3 * KB;
        PROVIDER.registerRecording(recordingFileId, actualSize);
        StagedItem item = persist(recordingFileId, null);

        awaitDelivered(item);

        assertThat(PROVIDER.metadataCallCount(recordingFileId)).isEqualTo(1);
        assertThat(stagedItemRepository.findById(item.getId()).orElseThrow().getResolvedSizeBytes())
                .as("the lookup result is persisted so a later retry costs nothing (FR-049)")
                .isEqualTo(actualSize);
    }

    @Test
    @DisplayName("an implausible declared size (negative) falls back to a metadata lookup rather than being trusted")
    void implausibleDeclaredSizeFallsBackToLookup() {
        String recordingFileId = "implausible-" + System.nanoTime();
        long actualSize = 4 * KB;
        PROVIDER.registerRecording(recordingFileId, actualSize);
        StagedItem item = persist(recordingFileId, -1L);

        awaitDelivered(item);

        assertThat(PROVIDER.metadataCallCount(recordingFileId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a declared size that understates the true payload still completes correctly (FR-050)")
    void understatedDeclaredSizeStillCompletes() {
        String recordingFileId = "understated-" + System.nanoTime();
        long actualSize = 8 * MB; // above the 5 MB test threshold -> should end up chunked
        PROVIDER.registerRecording(recordingFileId, actualSize);
        // Declare far below the threshold so the worker's first attempt takes the single-request path.
        StagedItem item = persist(recordingFileId, 1 * KB);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(stagedItemRepository.findById(item.getId()).orElseThrow().getDeliveryState())
                        .isEqualTo(DeliveryState.DELIVERED));

        StagedItem delivered = stagedItemRepository.findById(item.getId()).orElseThrow();
        assertThat(delivered.getVerifiedSizeBytes()).isEqualTo(actualSize);
    }

    private void awaitDelivered(StagedItem item) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(stagedItemRepository.findById(item.getId()).orElseThrow().getDeliveryState())
                        .isEqualTo(DeliveryState.DELIVERED));
    }

    private StagedItem persist(String recordingFileId, Long declaredSize) {
        StagedItem item = CopyTestFixtures.stagedItemBuilder(recordingFileId)
                .destinationBucket(BUCKET)
                .destinationKey("recordings/" + recordingFileId)
                .declaredSizeBytes(declaredSize)
                .build();
        return stagedItemRepository.save(item);
    }
}
