package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.staging.DeliveryState;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Releases claims whose holder has stopped making progress (FR-017) — a worker killed mid-transfer
 * leaves {@code claim_expires_at} in the past, since nothing extends it once the process is gone.
 * Reclaiming uses the identical transition as an ordinary transient-failure retry: increment
 * {@code attempt_count}, apply backoff, clear the claim (data-model.md §1.2).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class ClaimReaper implements SmartLifecycle {

    private final StagedItemRepository repository;
    private final CopyProperties properties;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;

    private ScheduledFuture<?> scheduledTask;
    private volatile boolean running;

    public ClaimReaper(StagedItemRepository repository,
                        CopyProperties properties,
                        Clock clock,
                        @Qualifier("copyPollerTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.scheduler = scheduler;
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
            reapExpiredClaims();
        } catch (Exception e) {
            log.error("Claim reaper scan failed", e);
        }
    }

    @Transactional
    public void reapExpiredClaims() {
        Instant now = Instant.now(clock);
        List<StagedItem> stale = repository.findStaleClaims(now);
        for (StagedItem item : stale) {
            int attempts = item.getAttemptCount() + 1;
            log.warn("Reaping expired claim on {} (owner={}, expired at {}, attempt {})",
                    item.getRecordingFileId(), item.getClaimOwner(), item.getClaimExpiresAt(), attempts);

            item.setAttemptCount(attempts);
            item.setClaimOwner(null);
            item.setClaimExpiresAt(null);
            item.setLastFailureReason("Claim expired: worker stopped making progress");
            item.setLastFailureAt(now);
            item.setUpdatedAt(now);

            if (attempts >= properties.delivery().maxAttempts()) {
                item.setDeliveryState(DeliveryState.PERMANENTLY_FAILED);
            } else {
                item.setDeliveryState(DeliveryState.AWAITING_DELIVERY);
                item.setNextAttemptAt(now.plus(BackoffCalculator.backoffFor(properties, attempts)));
            }
            repository.save(item);
        }
    }
}
