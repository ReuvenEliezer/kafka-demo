package com.reuven.kafka.demo.copy.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Micrometer registration for the feature's counters and timer (FR-058). Gauges are registered here
 * too (via {@link #gauge}) but bound by whichever story owns the live data source — backlog size and
 * age by {@code BacklogGovernor} (US2), unfinished-transfer counts by {@code AbandonedUploadReaper}
 * (US7) — so this class stays a thin Micrometer wrapper rather than depending on their repositories.
 */
@Component
public class CopyMetrics {

    private final MeterRegistry registry;
    private final Counter deliveryCompleted;
    private final Counter deliveryRetries;
    private final Counter deliveryFailedPermanent;
    private final Timer deliveryDuration;
    private final Counter checkpointErrors;

    public CopyMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.deliveryCompleted = Counter.builder("copy.delivery.completed")
                .description("Items successfully delivered and verified")
                .register(registry);
        this.deliveryRetries = Counter.builder("copy.delivery.retries")
                .description("Delivery attempts that failed and were rescheduled")
                .register(registry);
        this.deliveryFailedPermanent = Counter.builder("copy.delivery.failed.permanent")
                .description("Items that reached PERMANENTLY_FAILED")
                .register(registry);
        this.deliveryDuration = Timer.builder("copy.delivery.duration")
                .description("Wall-clock duration of a successful delivery attempt")
                .register(registry);
        this.checkpointErrors = Counter.builder("copy.checkpoint.errors")
                .description("Checkpoint store operations that failed")
                .register(registry);
    }

    public void recordDelivered(Duration duration) {
        deliveryCompleted.increment();
        deliveryDuration.record(duration);
    }

    public void recordRetry() {
        deliveryRetries.increment();
    }

    public void recordPermanentFailure() {
        deliveryFailedPermanent.increment();
    }

    public void recordCheckpointError() {
        checkpointErrors.increment();
    }

    public void recordReleaseOutcome(String outcome) {
        Counter.builder("copy.release.outcome")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /** Registers a gauge backed by a live supplier — the owning story provides the supplier. */
    public void gauge(String name, String description, Supplier<Number> valueSupplier) {
        Gauge.builder(name, valueSupplier)
                .description(description)
                .register(registry);
    }
}
