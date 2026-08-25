package com.reuven.kafka.demo.copy.consumer;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.observability.CopyMetrics;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

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

    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused;

    public BacklogGovernor(StagedItemRepository repository,
                            KafkaListenerEndpointRegistry listenerRegistry,
                            CopyProperties properties,
                            CopyMetrics metrics,
                            Clock clock) {
        this.repository = repository;
        this.listenerRegistry = listenerRegistry;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @PostConstruct
    void registerGauges() {
        metrics.gauge("copy.backlog.size", "Staged items awaiting delivery", () -> (double) repository.countBacklog());
        metrics.gauge("copy.backlog.oldest.age", "Age in seconds of the oldest undelivered item", this::oldestUndeliveredAgeSeconds);
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::runLoop, "backlog-governor");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            try {
                check();
            } catch (Exception e) {
                log.error("Backlog governor check failed", e);
            }
            sleepQuietly(properties.backlog().checkInterval());
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

    private static void sleepQuietly(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
