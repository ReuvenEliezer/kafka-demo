package com.reuven.kafka.demo.copy.delivery;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.exception.PermanentCopyException;
import com.reuven.kafka.demo.copy.exception.TransientCopyException;
import com.reuven.kafka.demo.copy.exception.UnsupportedUploadPathException;
import com.reuven.kafka.demo.copy.observability.CopyMetrics;
import com.reuven.kafka.demo.copy.provider.ProviderClient;
import com.reuven.kafka.demo.copy.staging.DeliveryState;
import com.reuven.kafka.demo.copy.staging.ReleaseState;
import com.reuven.kafka.demo.copy.staging.StagedItem;
import com.reuven.kafka.demo.copy.staging.StagedItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Claims one staged item at a time per worker thread, transfers it, and drives its
 * {@link DeliveryState} transitions (research.md R18, FR-015-FR-019).
 *
 * <p>{@link #claim()} is a short, immediately-committed transaction — {@code SELECT ... FOR UPDATE
 * SKIP LOCKED} inside {@code StagedItemRepository#claimNext} — and nothing else runs inside it. The
 * transfer itself runs with no open transaction: a multi-hour upload cannot be allowed to pin a
 * database connection or hold a row lock for its whole duration.
 *
 * <p>A successful {@link ObjectUploader#upload} does not by itself earn {@code DELIVERED} —
 * {@link IntegrityVerifier} must also pass (all three layers, research.md R14) before the state
 * transitions. There is no path that marks an item delivered on the uploader's word alone.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class DeliveryWorker implements SmartLifecycle {

    private final StagedItemRepository repository;
    private final Map<UploadPath, ObjectUploader> uploaderRegistry;
    private final UploadPathSelector pathSelector;
    private final ProviderClient providerClient;
    private final IntegrityVerifier integrityVerifier;
    private final SizeResolver sizeResolver;
    private final CopyProperties properties;
    private final Clock clock;
    private final CopyMetrics metrics;
    private final String workerId = "delivery-worker-" + UUID.randomUUID();
    private final AsyncTaskExecutor executor;

    private final List<Future<?>> loopFutures = new ArrayList<>();
    private volatile boolean running;

    public DeliveryWorker(StagedItemRepository repository,
                           Map<UploadPath, ObjectUploader> uploaderRegistry,
                           UploadPathSelector pathSelector,
                           ProviderClient providerClient,
                           IntegrityVerifier integrityVerifier,
                           SizeResolver sizeResolver,
                           CopyProperties properties,
                           Clock clock,
                           CopyMetrics metrics,
                           @Qualifier("copyDeliveryTaskExecutor") AsyncTaskExecutor executor) {
        this.repository = repository;
        this.uploaderRegistry = uploaderRegistry;
        this.pathSelector = pathSelector;
        this.providerClient = providerClient;
        this.integrityVerifier = integrityVerifier;
        this.sizeResolver = sizeResolver;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Override
    public void start() {
        running = true;
        int concurrency = properties.delivery().workerConcurrency();
        if (concurrency == 0) {
            // A legitimate configuration (e.g. an ingress-only node, or a test driving claims
            // directly) — no background threads at all.
            return;
        }
        for (int i = 0; i < concurrency; i++) {
            loopFutures.add(executor.submit(this::runLoop));
        }
    }

    @Override
    public void stop() {
        running = false;
        loopFutures.forEach(future -> future.cancel(true));
        loopFutures.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            boolean processed;
            try {
                processed = pollOnce();
            } catch (Exception e) {
                log.error("Unexpected error in delivery worker poll loop", e);
                processed = false;
            }
            if (!processed) {
                sleepQuietly(properties.delivery().pollInterval());
            }
        }
    }

    boolean pollOnce() {
        Optional<StagedItem> claimed = claim();
        claimed.ifPresent(this::process);
        return claimed.isPresent();
    }

    @Transactional
    Optional<StagedItem> claim() {
        Instant now = Instant.now(clock);
        Instant claimExpiresAt = now.plus(properties.delivery().claimTimeout());
        return repository.claimNext(workerId, claimExpiresAt, now);
    }

    private void process(StagedItem item) {
        Instant startedAt = Instant.now(clock);
        try {
            long sizeBytes = sizeResolver.resolve(item, providerClient);
            UploadPath path = pathSelector.select(sizeBytes);
            ObjectUploader uploader = uploaderRegistry.get(path);
            if (uploader == null) {
                throw new UnsupportedUploadPathException(
                        "No ObjectUploader registered for " + path + " (recordingFileId=" + item.getRecordingFileId() + ")");
            }

            UploadOutcome outcome = uploader.upload(new UploadRequest(item, providerClient, sizeBytes));
            integrityVerifier.verify(item.getDestinationBucket(), item.getDestinationKey(), sizeBytes, outcome);
            markDelivered(item, outcome);
            metrics.recordDelivered(Duration.between(startedAt, Instant.now(clock)));
        } catch (PermanentCopyException e) {
            log.warn("Item {} failed permanently: {}", item.getRecordingFileId(), e.getMessage());
            markPermanentlyFailed(item, e);
            metrics.recordPermanentFailure();
        } catch (TransientCopyException e) {
            log.info("Item {} failed transiently, scheduling retry: {}", item.getRecordingFileId(), e.getMessage());
            scheduleRetry(item, e);
        } catch (Exception e) {
            log.error("Unclassified failure delivering item {}, treating as transient", item.getRecordingFileId(), e);
            scheduleRetry(item, e);
        }
    }

    @Transactional
    void markDelivered(StagedItem item, UploadOutcome outcome) {
        Instant now = Instant.now(clock);
        item.setDeliveryState(DeliveryState.DELIVERED);
        item.setDeliveredAt(now);
        item.setVerifiedChecksum(outcome.fullObjectChecksumCrc32c());
        item.setVerifiedSizeBytes(outcome.bytesUploaded());
        item.setReleaseState(ReleaseState.PENDING);
        item.setClaimOwner(null);
        item.setClaimExpiresAt(null);
        item.setUpdatedAt(now);
        repository.save(item);
    }

    @Transactional
    void markPermanentlyFailed(StagedItem item, Exception cause) {
        Instant now = Instant.now(clock);
        item.setDeliveryState(DeliveryState.PERMANENTLY_FAILED);
        item.setLastFailureReason(truncate(cause.getMessage()));
        item.setLastFailureAt(now);
        item.setClaimOwner(null);
        item.setClaimExpiresAt(null);
        item.setUpdatedAt(now);
        repository.save(item);
    }

    @Transactional
    void scheduleRetry(StagedItem item, Exception cause) {
        Instant now = Instant.now(clock);
        int attempts = item.getAttemptCount() + 1;
        item.setAttemptCount(attempts);
        item.setLastFailureReason(truncate(cause.getMessage()));
        item.setLastFailureAt(now);
        item.setClaimOwner(null);
        item.setClaimExpiresAt(null);
        item.setUpdatedAt(now);

        if (attempts >= properties.delivery().maxAttempts()) {
            item.setDeliveryState(DeliveryState.PERMANENTLY_FAILED);
            repository.save(item);
            metrics.recordPermanentFailure();
        } else {
            item.setDeliveryState(DeliveryState.AWAITING_DELIVERY);
            item.setNextAttemptAt(now.plus(BackoffCalculator.backoffFor(properties, attempts)));
            repository.save(item);
            metrics.recordRetry();
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        int max = 2000;
        return message.length() > max ? message.substring(0, max) : message;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
