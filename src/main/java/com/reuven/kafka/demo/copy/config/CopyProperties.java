package com.reuven.kafka.demo.copy.config;

import com.reuven.kafka.demo.copy.exception.InvalidCheckpointExpiryException;
import com.reuven.kafka.demo.copy.exception.InvalidChunkingThresholdException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Root of the {@code copy.*} configuration surface (contracts/configuration.md). The compact
 * constructor performs every cross-field validation (V1-V6) so an invalid combination fails
 * binding and the context never starts (research.md R5, FR-025, FR-039, FR-040, FR-041).
 *
 * <p><b>V5 and V6 scope</b>: the contract phrases these as applying "when the staged strategy is
 * active" / "when the endpoint is enabled". There is no separate {@code copy.notification.enabled}
 * flag in the configuration surface, so both are read here as gated on
 * {@code copy.consumer.strategy == staged} — the inline strategy must start with zero additional
 * configuration (FR-004), and the notification ingress only matters operationally once the staged
 * strategy is the one consuming what it publishes.
 */
@ConfigurationProperties("copy")
@Validated
public record CopyProperties(
        @NotNull Consumer consumer,
        @NotNull Backlog backlog,
        @NotNull Delivery delivery,
        @NotNull Chunking chunking,
        @NotNull Checkpoint checkpoint,
        @NotNull Cleanup cleanup,
        @NotNull Size size,
        @NotNull Provider provider,
        @NotNull Notification notification,
        @NotNull Destination destination
) {

    private static final DataSize MAX_SINGLE_REQUEST = DataSize.ofGigabytes(5);
    private static final int MIN_NOTIFICATION_SECRET_LENGTH = 32;

    public CopyProperties {
        // V1 (FR-025): threshold <= 5 GiB, the S3 single-request maximum.
        if (chunking.threshold().compareTo(MAX_SINGLE_REQUEST) > 0) {
            throw new InvalidChunkingThresholdException(
                    "copy.chunking.threshold (%s) must not exceed the S3 single-request maximum of %s"
                            .formatted(chunking.threshold(), MAX_SINGLE_REQUEST));
        }

        Duration maxRetrySpan = delivery.maxBackoff().multipliedBy(delivery.maxAttempts());

        // V2 (FR-039): checkpoint expiry must outlive the longest possible retry span.
        if (checkpoint.expiry().compareTo(maxRetrySpan) <= 0) {
            throw new InvalidCheckpointExpiryException(
                    "copy.checkpoint.expiry (%s) must exceed the maximum retry span (copy.delivery.max-attempts %d x copy.delivery.max-backoff %s = %s)"
                            .formatted(checkpoint.expiry(), delivery.maxAttempts(), delivery.maxBackoff(), maxRetrySpan));
        }

        // V3 (FR-040): a checkpoint must never outlive the partial upload it references.
        if (checkpoint.expiry().compareTo(cleanup.abandonedUploadRetention()) >= 0) {
            throw new InvalidCheckpointExpiryException(
                    "copy.checkpoint.expiry (%s) must be less than copy.cleanup.abandoned-upload-retention (%s)"
                            .formatted(checkpoint.expiry(), cleanup.abandonedUploadRetention()));
        }

        // V4 (FR-012): hysteresis requires a strict ordering, or a single threshold would flap.
        if (backlog.lowWaterMark() >= backlog.highWaterMark()) {
            throw new InvalidCheckpointExpiryException(
                    "copy.backlog.low-water-mark (%d) must be less than copy.backlog.high-water-mark (%d)"
                            .formatted(backlog.lowWaterMark(), backlog.highWaterMark()));
        }

        if (consumer.strategy() == ConsumptionStrategy.STAGED) {
            // V5 (FR-062): an empty allowlist would silently permit every host.
            if (provider.allowedHosts() == null || provider.allowedHosts().isEmpty()) {
                throw new InvalidCheckpointExpiryException(
                        "copy.provider.allowed-hosts must be non-empty when copy.consumer.strategy is staged");
            }

            // V6 (FR-082): a short or absent secret defeats the point of signing.
            if (notification.secret() == null || notification.secret().length() < MIN_NOTIFICATION_SECRET_LENGTH) {
                throw new InvalidCheckpointExpiryException(
                        "copy.notification.secret must be present and at least %d characters (from %s only, never a config file) when copy.consumer.strategy is staged"
                                .formatted(MIN_NOTIFICATION_SECRET_LENGTH, "COPY_NOTIFICATION_SECRET"));
            }
        }
    }

    public record Consumer(
            @DefaultValue("inline") ConsumptionStrategy strategy,
            @DefaultValue("recording-copy") String topic,
            @DefaultValue("recording-copy-group") String groupId,
            @NotNull Batch batch
    ) {
        public record Batch(
                @DefaultValue("100") @Min(1) int maxRecords,
                @DefaultValue("1s") Duration maxWait
        ) {
        }
    }

    public record Backlog(
            @DefaultValue("10000") @Min(1) int highWaterMark,
            @DefaultValue("5000") @Min(0) int lowWaterMark,
            @DefaultValue("5s") Duration checkInterval
    ) {
    }

    public record Delivery(
            /* 0 is a legitimate value: no background delivery threads at all (e.g. an
               ingress-only node, a maintenance window, or a test driving claims directly). */
            @DefaultValue("4") @Min(0) int workerConcurrency,
            @DefaultValue("2s") Duration pollInterval,
            @DefaultValue("10") @Min(1) int maxAttempts,
            @DefaultValue("10s") Duration initialBackoff,
            @DefaultValue("30m") Duration maxBackoff,
            @DefaultValue("5m") Duration claimTimeout
    ) {
    }

    public record Chunking(
            @DefaultValue("100MB") @DataSizeUnit(DataUnit.MEGABYTES) DataSize threshold,
            @DefaultValue("16MB") @DataSizeUnit(DataUnit.MEGABYTES) DataSize basePartSize
    ) {
    }

    public record Checkpoint(
            @DefaultValue("24h") Duration expiry,
            @DefaultValue("xfer") String keyPrefix
    ) {
    }

    public record Cleanup(
            @DefaultValue("7d") Duration abandonedUploadRetention,
            @DefaultValue("1h") Duration scanInterval
    ) {
    }

    public record Size(
            @DefaultValue("5TB") @DataSizeUnit(DataUnit.TERABYTES) DataSize maxPlausibleBytes
    ) {
    }

    public record Provider(
            String baseUrl,
            List<String> allowedHosts,
            @DefaultValue("5m") Duration credentialRenewalMargin,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout
    ) {
    }

    public record Notification(
            @DefaultValue("/provider/notifications") String path,
            String secret,
            @DefaultValue("5m") Duration freshnessWindow,
            @DefaultValue("1MB") @DataSizeUnit(DataUnit.MEGABYTES) DataSize maxBodySize
    ) {
    }

    public record Destination(
            String bucket,
            @DefaultValue("recordings") String keyPrefix
    ) {
    }
}
