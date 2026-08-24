package com.reuven.kafka.demo;

import com.reuven.kafka.demo.services.KafkaConsumer;
import com.reuven.kafka.demo.services.KafkaProducer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Simulates a network outage against S3 (via LocalStack) to verify the s3Upload circuit
 * breaker trips and pauses the Kafka consumer, then verify it recovers once S3 is reachable again.
 */
@SpringBootTest
@Testcontainers
class S3CircuitBreakerIntegrationTest {

    private static final String TOPIC = "test-topic";
    private static final String BUCKET = "kafka-demo-events-test";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.1"));

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
            .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.topic", () -> TOPIC);
        registry.add("aws.s3.region", () -> LOCALSTACK.getRegion());
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString());
    }

    @Autowired
    private KafkaProducer producer;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerRegistry;

    @Test
    void pausesConsumerOnSustainedS3TimeoutsAndResumesOnRecovery() throws Exception {
        LOCALSTACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET);

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("s3Upload");
        MessageListenerContainer container = kafkaListenerRegistry.getListenerContainer(KafkaConsumer.LISTENER_ID);
        assertThat(container).isNotNull();

        // Phase 0: an isolated blip (one timeout among healthy calls) must not trip the breaker.
        // sliding-window-size=4, minimum-number-of-calls=4, failure-rate-threshold=50%, so 1/4 stays CLOSED.
        LOCALSTACK.getDockerClient().pauseContainerCmd(LOCALSTACK.getContainerId()).exec();
        producer.sendMessage("blip-event", false).get();
        Thread.sleep(1500); // longer than aws.s3.api-call-timeout (1s in test config) to force one real timeout
        LOCALSTACK.getDockerClient().unpauseContainerCmd(LOCALSTACK.getContainerId()).exec();
        int lastBlipRecoveryEventId = -1;
        for (int i = 0; i < 3; i++) {
            lastBlipRecoveryEventId = producer.sendMessage("blip-recovery-" + i, false).get();
        }
        awaitObjectExists(lastBlipRecoveryEventId);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(container.isContainerPaused()).isFalse();

        // Phase 1: S3 healthy - message is archived and the circuit breaker stays closed.
        int healthyEventId = producer.sendMessage("healthy-event", false).get();
        awaitObjectExists(healthyEventId);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(container.isContainerPaused()).isFalse();

        // Phase 2: simulate a network outage by freezing the LocalStack container so calls hang
        // until the configured api-call-timeout elapses, producing SdkClientException timeouts.
        LOCALSTACK.getDockerClient().pauseContainerCmd(LOCALSTACK.getContainerId()).exec();
        int duringPauseEventId;
        try {
            for (int i = 0; i < 4; i++) {
                producer.sendMessage("event-during-outage-" + i, false).get();
            }

            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(container.isContainerPaused()).isTrue());

            // Bounded backlog (SC-003): once paused, the consumer must not keep pulling and blindly
            // retrying against the known-down target - a message sent while paused should sit
            // unconsumed in Kafka, not get archived, even the instant S3 becomes reachable again
            // (checked below before the breaker's own recovery has had a chance to resume the flow).
            duringPauseEventId = producer.sendMessage("event-while-paused", false).get();
        } finally {
            LOCALSTACK.getDockerClient().unpauseContainerCmd(LOCALSTACK.getContainerId()).exec();
        }
        assertThat(container.isContainerPaused()).isTrue();
        assertThatObjectDoesNotExist(duringPauseEventId);

        // Phase 3: S3 is reachable again - the breaker should probe, close, and the consumer should resume.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(container.isContainerPaused()).isFalse());

        int recoveredEventId = producer.sendMessage("recovered-event", false).get();
        awaitObjectExists(recoveredEventId);
    }

    private void awaitObjectExists(int eventId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            try {
                s3Client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(eventId + ".json").build());
            } catch (NoSuchKeyException e) {
                throw new AssertionError("object for event " + eventId + " not yet archived", e);
            }
        });
    }

    private void assertThatObjectDoesNotExist(int eventId) {
        assertThat(s3Client.listObjectsV2(b -> b.bucket(BUCKET).prefix(eventId + ".json")).contents())
                .as("event id=%d should not have been archived while the consumer is paused", eventId)
                .isEmpty();
    }

}
