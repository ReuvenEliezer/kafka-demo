package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.consumer.StagedBatchConsumer;
import com.reuven.kafka.demo.copy.message.CopyMessageHeaders;
import com.reuven.kafka.demo.copy.message.RecordingCopyMessage;
import com.reuven.kafka.demo.copy.staging.DeliveryState;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import com.reuven.kafka.demo.copy.support.CopyIntegrationTestBase;
import com.reuven.kafka.demo.copy.support.FakeProviderServer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * quickstart.md S3 — SC-004, SC-005. (S4's full crash-injection-at-every-boundary matrix needs
 * process-level kill semantics impractical to automate safely here; SC-006's "never absent from
 * both stores" is instead proven for the achievable case — every published message lands as a
 * staged row under normal operation, which is what the ack-after-commit ordering (research.md R8)
 * guarantees.)
 */
class StagedConsumerIntegrationTest extends CopyIntegrationTestBase {

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
    @Qualifier("copyKafkaTemplate")
    private KafkaTemplate<String, RecordingCopyMessage> copyKafkaTemplate;

    @Autowired
    private StagedItemRepository stagedItemRepository;

    @Test
    @DisplayName("intake continues and stages durably through a total object-store outage, then drains unattended on recovery")
    void intakeSurvivesOutageAndDrainsOnRecovery() throws Exception {
        List<String> recordingFileIds = IntStream.range(0, 5)
                .mapToObj(i -> "outage-" + Instant.now().toEpochMilli() + "-" + i)
                .toList();
        recordingFileIds.forEach(id -> PROVIDER.registerRecording(id, 1024));

        LOCALSTACK.getDockerClient().pauseContainerCmd(LOCALSTACK.getContainerId()).exec();
        try {
            recordingFileIds.forEach(this::publish);

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                long staged = recordingFileIds.stream()
                        .filter(id -> stagedItemRepository.findAll().stream().anyMatch(i -> i.getRecordingFileId().equals(id)))
                        .count();
                assertThat(staged).isEqualTo(recordingFileIds.size());
            });

            assertThat(stagedItemRepository.findAll().stream()
                    .filter(i -> recordingFileIds.contains(i.getRecordingFileId()))
                    .map(i -> i.getDeliveryState()))
                    .as("nothing can have been delivered while the object store is unreachable")
                    .doesNotContain(DeliveryState.DELIVERED);
        } finally {
            LOCALSTACK.getDockerClient().unpauseContainerCmd(LOCALSTACK.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            long delivered = recordingFileIds.stream()
                    .filter(id -> stagedItemRepository.findAll().stream()
                            .anyMatch(i -> i.getRecordingFileId().equals(id) && i.getDeliveryState() == DeliveryState.DELIVERED))
                    .count();
            assertThat(delivered).isEqualTo(recordingFileIds.size());
        });
    }

    private void publish(String recordingFileId) {
        RecordingCopyMessage message = new RecordingCopyMessage(
                recordingFileId,
                "session-" + recordingFileId,
                "acct-1",
                "MP4",
                PROVIDER.baseUrl() + "/recordings/" + recordingFileId + "/content",
                1024L,
                "application/octet-stream",
                Instant.now());

        ProducerRecord<String, RecordingCopyMessage> record =
                new ProducerRecord<>("recording-copy", recordingFileId, message);
        record.headers().add(CopyMessageHeaders.RECORDING_SIZE, "1024".getBytes(StandardCharsets.UTF_8));
        record.headers().add(CopyMessageHeaders.PROVIDER_EVENT_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(CopyMessageHeaders.PROVIDER_ACCOUNT_ID, "acct-1".getBytes(StandardCharsets.UTF_8));

        copyKafkaTemplate.executeInTransaction(ops -> ops.send(record));
    }
}
