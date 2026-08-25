package com.reuven.kafka.demo.copy.cleanup;

import com.reuven.kafka.demo.copy.config.CopyProperties;
import com.reuven.kafka.demo.copy.observability.CopyMetrics;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.paginators.ListMultipartUploadsIterable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reclaims partial upload state S3 never expires on its own (FR-055–FR-057). The retention window
 * is strictly longer than the maximum retry span by construction —
 * {@code CopyProperties}' startup validation (V2, V3) already enforces
 * {@code maxRetrySpan < checkpoint.expiry < abandoned-upload-retention} — so a transfer still
 * eligible for retry can never be reaped without any runtime check needed here (FR-056).
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "copy.consumer.strategy", havingValue = "staged")
public class AbandonedUploadReaper implements SmartLifecycle {

    private final S3Client deliveryS3Client;
    private final CopyProperties properties;
    private final CopyMetrics metrics;
    private final Clock clock;

    private final AtomicLong unfinishedCount = new AtomicLong();
    private final AtomicLong unfinishedBytes = new AtomicLong();

    private Thread thread;
    private volatile boolean running;

    public AbandonedUploadReaper(@Qualifier("deliveryS3Client") S3Client deliveryS3Client,
                                  CopyProperties properties,
                                  CopyMetrics metrics,
                                  Clock clock) {
        this.deliveryS3Client = deliveryS3Client;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @PostConstruct
    void registerGauges() {
        metrics.gauge("copy.transfers.unfinished",
                "Count of in-progress multipart uploads still within their retention window",
                () -> (double) unfinishedCount.get());
        metrics.gauge("copy.transfers.unfinished.bytes",
                "Total bytes uploaded so far across unfinished multipart uploads",
                () -> (double) unfinishedBytes.get());
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::runLoop, "abandoned-upload-reaper");
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
                scan();
            } catch (Exception e) {
                log.error("Abandoned upload scan failed", e);
            }
            sleepQuietly(properties.cleanup().scanInterval());
        }
    }

    public void scan() {
        String bucket = properties.destination().bucket();
        Instant cutoff = Instant.now(clock).minus(properties.cleanup().abandonedUploadRetention());

        long count = 0;
        long bytes = 0;
        ListMultipartUploadsIterable pages = deliveryS3Client.listMultipartUploadsPaginator(
                ListMultipartUploadsRequest.builder().bucket(bucket).build());

        for (MultipartUpload upload : pages.uploads()) {
            if (upload.initiated() != null && upload.initiated().isBefore(cutoff)) {
                abortAndLog(bucket, upload);
            } else {
                count++;
                bytes += sumPartsSize(bucket, upload);
            }
        }

        unfinishedCount.set(count);
        unfinishedBytes.set(bytes);
    }

    private long sumPartsSize(String bucket, MultipartUpload upload) {
        try {
            return deliveryS3Client.listParts(b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()))
                    .parts().stream().mapToLong(Part::size).sum();
        } catch (SdkException e) {
            log.warn("Failed to list parts for unfinished upload {} ({}): {}", upload.uploadId(), upload.key(), e.getMessage());
            return 0;
        }
    }

    private void abortAndLog(String bucket, MultipartUpload upload) {
        try {
            deliveryS3Client.abortMultipartUpload(b -> b.bucket(bucket).key(upload.key()).uploadId(upload.uploadId()));
            log.warn("Aborted abandoned multipart upload key={} uploadId={} initiated={}",
                    upload.key(), upload.uploadId(), upload.initiated());
        } catch (SdkException e) {
            log.error("Failed to abort abandoned multipart upload {} ({}): {}", upload.uploadId(), upload.key(), e.getMessage());
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
