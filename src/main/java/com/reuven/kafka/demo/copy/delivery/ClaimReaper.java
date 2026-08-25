package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.staging.DeliveryState;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private Thread thread;
    private volatile boolean running;

    public ClaimReaper(StagedItemRepository repository, CopyProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::runLoop, "claim-reaper");
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
                reapExpiredClaims();
            } catch (Exception e) {
                log.error("Claim reaper scan failed", e);
            }
            sleepQuietly(properties.delivery().pollInterval());
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

    private static void sleepQuietly(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
