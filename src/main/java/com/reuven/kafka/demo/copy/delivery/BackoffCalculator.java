package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;

import java.time.Duration;

/**
 * Exponential backoff shared by {@link DeliveryWorker}'s retry path, {@code ClaimReaper}'s reclaim
 * (data-model.md notes a reclaimed expired claim "uses the same transition" as an ordinary
 * transient-failure retry, backoff included — FR-018), and {@code ReleaseSignalService}'s retry,
 * which has no dedicated backoff config of its own (FR-067) and reuses this shape rather than
 * inventing a second one.
 */
public final class BackoffCalculator {

    private BackoffCalculator() {
    }

    public static Duration backoffFor(CopyProperties properties, int attemptCount) {
        Duration initial = properties.delivery().initialBackoff();
        Duration max = properties.delivery().maxBackoff();
        long multiplier = 1L << Math.min(attemptCount, 30);
        Duration candidate = initial.multipliedBy(multiplier);
        return candidate.compareTo(max) > 0 ? max : candidate;
    }
}
