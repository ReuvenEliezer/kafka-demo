package com.reuven.kafka.demo.copy.provider;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.delivery.BackoffCalculator;
import com.reuven.kafka.demo.copy.observability.CopyMetrics;
import com.reuven.kafka.demo.copy.staging.ReleaseState;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Signals the provider that it may discard its source copy — the one irreversible action in the
 * feature (FR-064–FR-068). The precondition is structural, not a check here: {@code release_state}
 * leaves {@code NOT_APPLICABLE} only when {@code DeliveryWorker} sets {@code delivery_state} to
 * {@code DELIVERED}, which itself only happens after {@code IntegrityVerifier} passes. A release
 * failure here only ever touches {@code release_state} — {@code delivery_state} and the payload are
 * untouched (FR-068, invariant I7), because the two state machines are separate fields with no
 * shared transition.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class ReleaseSignalService implements SmartLifecycle {

    private final StagedItemRepository repository;
    private final ProviderClient providerClient;
    private final CopyProperties properties;
    private final CopyMetrics metrics;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;

    private ScheduledFuture<?> scheduledTask;
    private volatile boolean running;

    public ReleaseSignalService(StagedItemRepository repository,
                                 ProviderClient providerClient,
                                 CopyProperties properties,
                                 CopyMetrics metrics,
                                 Clock clock,
                                 @Qualifier("copyPollerTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.repository = repository;
        this.providerClient = providerClient;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void registerGauge() {
        metrics.gauge("copy.release.pending", "Items delivered but not yet released — a distinct condition from fully complete (FR-067)",
                () -> (double) repository.countByReleaseState(ReleaseState.PENDING));
    }

    @Override
    public void start() {
        running = true;
        scheduledTask = scheduler.scheduleWithFixedDelay(this::runOnce, properties.delivery().pollInterval());
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
            processPendingReleases();
        } catch (Exception e) {
            log.error("Release signal scan failed", e);
        }
    }

    void processPendingReleases() {
        Instant now = Instant.now(clock);
        repository.findByReleaseState(ReleaseState.PENDING).forEach(this::processOne);
        repository.findByReleaseState(ReleaseState.RELEASE_FAILED).stream()
                .filter(item -> readyForRetry(item, now))
                .forEach(this::processOne);
    }

    private boolean readyForRetry(StagedItem item, Instant now) {
        Duration backoff = BackoffCalculator.backoffFor(properties, item.getReleaseAttemptCount());
        return item.getUpdatedAt() == null || !item.getUpdatedAt().plus(backoff).isAfter(now);
    }

    @Transactional
    void processOne(StagedItem item) {
        ReleaseOutcome outcome;
        try {
            outcome = providerClient.signalRelease(item.getRecordingFileId());
        } catch (Exception e) {
            log.warn("Release signal threw for {}: {}", item.getRecordingFileId(), e.getMessage());
            outcome = ReleaseOutcome.TRANSIENT_FAILURE;
        }

        Instant now = Instant.now(clock);
        item.setUpdatedAt(now);

        switch (outcome) {
            case RELEASED, ALREADY_RELEASED -> item.setReleaseState(ReleaseState.RELEASED);
            case TRANSIENT_FAILURE, PERMANENT_FAILURE -> {
                item.setReleaseState(ReleaseState.RELEASE_FAILED);
                item.setReleaseAttemptCount(item.getReleaseAttemptCount() + 1);
                item.setReleaseLastError("Release signal outcome: " + outcome);
            }
        }
        metrics.recordReleaseOutcome(outcome.name());
        repository.save(item);
    }
}
