package com.reuven.kafka.demo.copy;

import com.reuven.kafka.demo.copy.consumer.BacklogGovernor;
import com.reuven.kafka.demo.copy.consumer.StagedBatchConsumer;
import com.reuven.kafka.demo.copy.staging.DeliveryState;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import com.reuven.kafka.demo.copy.support.CopyIntegrationTestBase;
import com.reuven.kafka.demo.copy.support.CopyTestFixtures;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * quickstart.md S8 — SC-013. Backlog pause/resume must never cause a consumer-group reassignment.
 * Asserted on {@code getAssignedPartitions()} staying identical across pause/resume, not merely on
 * {@code isContainerPaused()} — a rebalance would revoke and (even if ultimately reassigning the same
 * partitions) is exactly the silent regression this test exists to catch. {@code container.pause()}
 * suspending record delivery while {@code poll()} keeps running, with no rebalance, is a documented
 * Spring Kafka guarantee (also relied on by the existing {@code S3CircuitBreakerIntegrationTest}).
 */
class BacklogGovernorIntegrationTest extends CopyIntegrationTestBase {

    @DynamicPropertySource
    static void thresholds(DynamicPropertyRegistry registry) {
        registry.add("copy.backlog.high-water-mark", () -> 20);
        registry.add("copy.backlog.low-water-mark", () -> 5);
        registry.add("copy.backlog.check-interval", () -> "1s");
    }

    @Autowired
    private StagedItemRepository repository;

    @Autowired
    private BacklogGovernor backlogGovernor;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void pausesAboveHighWaterMarkAndResumesBelowLowWaterMarkWithNoRebalance() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(StagedBatchConsumer.LISTENER_ID);
        assertThat(container).isNotNull();

        await().untilAsserted(() -> assertThat(container.isContainerPaused()).isFalse());
        await().untilAsserted(() -> assertThat(container.getAssignedPartitions()).isNotEmpty());
        Collection<TopicPartition> assignedBefore = container.getAssignedPartitions();

        insertAwaitingItems(25);
        await().untilAsserted(() -> assertThat(container.isContainerPaused()).isTrue());
        assertThat(container.getAssignedPartitions())
                .as("pausing must not trigger a consumer-group reassignment")
                .isEqualTo(assignedBefore);

        markAllDelivered();
        await().untilAsserted(() -> assertThat(container.isContainerPaused()).isFalse());
        assertThat(container.getAssignedPartitions())
                .as("resuming must not trigger a consumer-group reassignment either")
                .isEqualTo(assignedBefore);
    }

    private void insertAwaitingItems(int count) {
        IntStream.range(0, count).forEach(i ->
                repository.save(CopyTestFixtures.stagedItemBuilder("backlog-" + Instant.now().toEpochMilli() + "-" + i).build()));
    }

    private void markAllDelivered() {
        List<StagedItem> items = repository.findAll();
        items.forEach(item -> item.setDeliveryState(DeliveryState.DELIVERED));
        repository.saveAll(items);
    }
}
