package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.staging.DeliveryState;
import com.reuven.kafka.demo.copy.staging.ReleaseState;
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
 * quickstart.md S11 — SC-018, the "hard zero". Drives the real background {@code DeliveryWorker} and
 * {@code ReleaseSignalService} rather than calling either directly, since the gate under test is the
 * wiring between them (release_state leaves NOT_APPLICABLE only when DeliveryWorker sets DELIVERED).
 *
 * <p>Scope note: exercises the invariant via a permanent-failure case (deleted recording) and a
 * clean success case, rather than fault-injecting at every one of mid-chunk / before-finalization /
 * during-finalization individually — those are already covered piecemeal by
 * {@code ResumableUploadIntegrationTest} and {@code CheckpointLifecycleTest}; what's specific to this
 * test is the release-signal count, which the two cases here bound at both ends (zero, and exactly one).
 */
class ReleaseSignalGatingTest extends CopyIntegrationTestBase {

    private static final String BUCKET = "kafka-demo-events-test";

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
    private StagedItemRepository repository;

    @Test
    @DisplayName("an item that never reaches DELIVERED gets a hard zero release signals")
    void neverDeliveredMeansZeroReleaseSignals() {
        String recordingFileId = "never-delivered-" + System.nanoTime();
        // No registerRecording() call: every credential/download request 404s, so the item fails
        // permanently on its very first attempt without ever reaching DELIVERED.
        StagedItem item = persist(recordingFileId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findById(item.getId()).orElseThrow().getDeliveryState())
                        .isEqualTo(DeliveryState.PERMANENTLY_FAILED));

        assertThat(PROVIDER.releaseSignalsReceived(recordingFileId)).isZero();
        assertThat(repository.findById(item.getId()).orElseThrow().getReleaseState())
                .isEqualTo(ReleaseState.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a successfully delivered item receives exactly one release signal")
    void deliveredItemReceivesExactlyOneReleaseSignal() {
        String recordingFileId = "delivered-" + System.nanoTime();
        PROVIDER.registerRecording(recordingFileId, 1024);
        StagedItem item = persist(recordingFileId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findById(item.getId()).orElseThrow().getDeliveryState())
                        .isEqualTo(DeliveryState.DELIVERED));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(repository.findById(item.getId()).orElseThrow().getReleaseState())
                        .isEqualTo(ReleaseState.RELEASED));

        assertThat(PROVIDER.releaseSignalsReceived(recordingFileId)).isEqualTo(1);
    }

    private StagedItem persist(String recordingFileId) {
        StagedItem item = CopyTestFixtures.stagedItemBuilder(recordingFileId)
                .destinationBucket(BUCKET)
                .destinationKey("recordings/" + recordingFileId)
                .declaredSizeBytes(1024L)
                .build();
        return repository.save(item);
    }
}
