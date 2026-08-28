package com.reuven.kafka.demo.copy.consumer;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.observability.CopyMetrics;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/**
 * Stops and resumes intake on staged-backlog pressure — the <b>only</b> thing that pauses the
 * staged strategy's consumer; object-store health never does (FR-012, FR-020, research.md R7,
 * R17). {@code container.pause()} keeps {@code poll()} running so the consumer stays in its group —
 * no rebalance (FR-013, SC-013).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class BacklogGovernor implements SmartLifecycle {

    private final StagedItemRepository repository;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final CopyProperties properties;
    private final CopyMetrics metrics;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;

    private ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private volatile boolean paused;

    public BacklogGovernor(StagedItemRepository repository,
                            KafkaListenerEndpointRegistry listenerRegistry,
                            CopyProperties properties,
                            CopyMetrics metrics,
                            Clock clock,
                            @Qualifier("copyPollerTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.repository = repository;
        this.listenerRegistry = listenerRegistry;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void registerGauges() {
        metrics.gauge("copy.backlog.size", "Staged items awaiting delivery", () -> (double) repository.countBacklog());
        metrics.gauge("copy.backlog.oldest.age", "Age in seconds of the oldest undelivered item", this::oldestUndeliveredAgeSeconds);
    }

    @Override
    public void start() {
        running = true;
        scheduledTask = scheduler.scheduleWithFixedDelay(this::runOnce, properties.backlog().checkInterval());
    }

    @Override
    public void stop() {
        running = false;
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            scheduledTask = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runOnce() {
        try {
            check();
        } catch (Exception e) {
            log.error("Backlog governor check failed", e);
        }
    }

    void check() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(StagedBatchConsumer.LISTENER_ID);
        if (container == null) {
            return;
        }

        long backlog = repository.countBacklog();
        if (!paused && backlog >= properties.backlog().highWaterMark()) {
            container.pause();
            paused = true;
            log.warn("BACKLOG_GOVERNOR_STATUS_CHANGE action=PAUSE reason=high-water-mark backlogSize={} highWaterMark={}",
                    backlog, properties.backlog().highWaterMark());
        } else if (paused && backlog < properties.backlog().lowWaterMark()) {
            container.resume();
            paused = false;
            log.warn("BACKLOG_GOVERNOR_STATUS_CHANGE action=RESUME reason=below-low-water-mark backlogSize={} lowWaterMark={}",
                    backlog, properties.backlog().lowWaterMark());
        }
    }

    private double oldestUndeliveredAgeSeconds() {
        return repository.findOldestUndeliveredCreatedAt()
                .map(createdAt -> (double) Duration.between(createdAt, Instant.now(clock)).toSeconds())
                .orElse(0.0);
    }
}
