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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * quickstart.md S9 and S10 — SC-016, SC-017, invariant I2. Also exercises {@code copy.checkpoint.expiry}
 * shorter than this test class's usual default (10s here, vs 60s package-wide), which is why it
 * carries its own {@code @DynamicPropertySource}: still valid against V2 (max-attempts 3 x
 * max-backoff 2s = 6s retry span < 10s expiry) and V3 (10s < 7d retention).
 */
class CheckpointLifecycleTest extends CopyIntegrationTestBase {

    private static final String BUCKET = "kafka-demo-events-test";
    private static final long MB = 1024L * 1024L;

    private static FakeProviderServer PROVIDER;

    @DynamicPropertySource
    static void providerAndExpiry(DynamicPropertyRegistry registry) throws IOException {
        PROVIDER = new FakeProviderServer();
        registry.add("copy.provider.base-url", PROVIDER::baseUrl);
        // Direct ChunkedUploader calls bypass the claim protocol — disable the real background
        // DeliveryWorker so it cannot race this test for the same staged item (see
        // ResumableUploadIntegrationTest for the full explanation).
        registry.add("copy.delivery.worker-concurrency", () -> 0);
        registry.add("copy.checkpoint.expiry", () -> "10s");
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
    private StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("S9: checkpoint expiry slides with progress — a transfer slower than the raw TTL still completes (SC-017)")
    void checkpointExpirySlidesWithProgress() {
        String recordingFileId = "sliding-ttl-" + System.nanoTime();
        long payloadSize = 15 * MB; // 3 parts of 5 MB (test config)
        PROVIDER.registerRecording(recordingFileId, payloadSize);
        // ~5s per part (640 chunks of 8 KiB at 8ms each) -> ~15s total, well past the 10s raw TTL.
        PROVIDER.setThrottle(recordingFileId, Duration.ofMillis(8));
        StagedItem item = persistStagedItem(recordingFileId, payloadSize);

        UploadOutcome outcome = chunkedUploader.upload(new UploadRequest(item, providerClient, payloadSize));

        assertThat(outcome.bytesUploaded()).isEqualTo(payloadSize);
        assertThat(checkpointStore.read(item.getDestinationBucket(), item.getDestinationKey())).isEmpty();
    }

    @Test
    @DisplayName("S10: flushing the checkpoint store costs only bytes — no object finalizes incomplete, no recording is lost (SC-016, I2)")
    void flushingCheckpointStoreCostsOnlyBytes() {
        String recordingFileId = "flush-mid-transfer-" + System.nanoTime();
        long payloadSize = 15 * MB; // 3 parts of 5 MB
        PROVIDER.registerRecording(recordingFileId, payloadSize);
        StagedItem item = persistStagedItem(recordingFileId, payloadSize);

        // Deterministic stand-in for "flushed mid-transfer": interrupt after part 1, flush the
        // entire checkpoint store (not just this key), then resume. The dangerous misreading this
        // guards against is a resume that treats the now-missing checkpoint as "done" rather than
        // "restart" — it must restart from part 1 and still produce a correct object.
        PROVIDER.setFailAfterBytes(recordingFileId, 5 * MB);
        assertThatThrownBy(() -> chunkedUploader.upload(new UploadRequest(item, providerClient, payloadSize)));
        assertThat(checkpointStore.read(item.getDestinationBucket(), item.getDestinationKey())).isPresent();

        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        assertThat(checkpointStore.read(item.getDestinationBucket(), item.getDestinationKey()))
                .as("flushed: absence must read as restart, never as completion (FR-032)")
                .isEmpty();

        UploadOutcome outcome = chunkedUploader.upload(new UploadRequest(item, providerClient, payloadSize));

        assertThat(outcome.bytesUploaded()).isEqualTo(payloadSize);
        assertThat(PROVIDER.bytesServed(recordingFileId))
                .as("first attempt (5 MB) + full restart after flush (15 MB) — costs bytes, nothing else")
                .isEqualTo(5 * MB + payloadSize);
        assertThat(checkpointStore.read(item.getDestinationBucket(), item.getDestinationKey())).isEmpty();
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
