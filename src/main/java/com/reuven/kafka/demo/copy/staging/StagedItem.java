package com.reuven.kafka.demo.copy.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per recording <b>file</b> to copy (data-model.md §1.1). Created by the staged consumer
 * inside the batch transaction, mutated by the delivery worker, read by the backlog governor and
 * metrics gauges.
 *
 * <p>A Lombok-annotated mutable class rather than a record — JPA needs a no-arg constructor,
 * mutability for managed-entity field updates, and proxy compatibility.
 *
 * <p>Holds the provider's <b>stable</b> recording identifier and never a captured download
 * credential, which would be expired by the time a long retry span elapsed (FR-059).
 */
@Entity
@Table(
        name = "staged_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_staged_item_file", columnNames = "recording_file_id"),
                @UniqueConstraint(name = "uq_staged_item_destination", columnNames = {"destination_bucket", "destination_key"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StagedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recording_file_id", nullable = false)
    private String recordingFileId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "provider_account_id", nullable = false)
    private String providerAccountId;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "destination_bucket", nullable = false)
    private String destinationBucket;

    @Column(name = "destination_key", nullable = false)
    private String destinationKey;

    @Column(name = "declared_size_bytes")
    private Long declaredSizeBytes;

    @Column(name = "resolved_size_bytes")
    private Long resolvedSizeBytes;

    @Column(name = "content_type")
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_state", nullable = false)
    @Builder.Default
    private DeliveryState deliveryState = DeliveryState.AWAITING_DELIVERY;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_failure_reason")
    private String lastFailureReason;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "claim_owner")
    private String claimOwner;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_state", nullable = false)
    @Builder.Default
    private ReleaseState releaseState = ReleaseState.NOT_APPLICABLE;

    @Column(name = "release_attempt_count", nullable = false)
    @Builder.Default
    private int releaseAttemptCount = 0;

    @Column(name = "release_last_error")
    private String releaseLastError;

    @Column(name = "verified_checksum")
    private String verifiedChecksum;

    @Column(name = "verified_size_bytes")
    private Long verifiedSizeBytes;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code coalesce(resolvedSizeBytes, declaredSizeBytes)} — the effective size the chunk planner uses. */
    public Long effectiveSizeBytes() {
        return resolvedSizeBytes != null ? resolvedSizeBytes : declaredSizeBytes;
    }
}
