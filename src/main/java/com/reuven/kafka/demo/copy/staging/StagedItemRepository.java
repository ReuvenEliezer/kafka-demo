package com.reuven.kafka.demo.copy.staging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StagedItemRepository extends JpaRepository<StagedItem, Long> {

    /**
     * Atomically claims the oldest eligible item: {@code SELECT ... FOR UPDATE SKIP LOCKED} inside
     * an {@code UPDATE ... RETURNING *} so the read-lock-write happens as one statement with no
     * window for a second worker to observe the same row (FR-016, invariant I5).
     */
    @Query(value = """
            UPDATE staged_item
               SET delivery_state = 'DELIVERY_IN_PROGRESS',
                   claim_owner = :claimOwner,
                   claim_expires_at = :claimExpiresAt,
                   updated_at = :now
             WHERE id = (
                    SELECT id
                      FROM staged_item
                     WHERE delivery_state = 'AWAITING_DELIVERY'
                       AND next_attempt_at <= :now
                     ORDER BY next_attempt_at ASC
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                   )
            RETURNING *
            """, nativeQuery = true)
    Optional<StagedItem> claimNext(@Param("claimOwner") String claimOwner,
                                    @Param("claimExpiresAt") Instant claimExpiresAt,
                                    @Param("now") Instant now);

    /** Backlog count gauge (FR-011): items still owed a delivery, claimed or not. */
    @Query("SELECT count(s) FROM StagedItem s WHERE s.deliveryState IN (com.reuven.kafka.demo.copy.staging.DeliveryState.AWAITING_DELIVERY, com.reuven.kafka.demo.copy.staging.DeliveryState.DELIVERY_IN_PROGRESS)")
    long countBacklog();

    /** The age of the oldest undelivered item is what distinguishes a healthy backlog from a stalled one. */
    @Query("SELECT min(s.createdAt) FROM StagedItem s WHERE s.deliveryState IN (com.reuven.kafka.demo.copy.staging.DeliveryState.AWAITING_DELIVERY, com.reuven.kafka.demo.copy.staging.DeliveryState.DELIVERY_IN_PROGRESS)")
    Optional<Instant> findOldestUndeliveredCreatedAt();

    /** The claim reaper's scan (FR-017): claims whose holder has stopped making progress. */
    @Query("SELECT s FROM StagedItem s WHERE s.deliveryState = com.reuven.kafka.demo.copy.staging.DeliveryState.DELIVERY_IN_PROGRESS AND s.claimExpiresAt < :now")
    List<StagedItem> findStaleClaims(@Param("now") Instant now);

    /** Delivered-but-unreleased items, surfaced as a distinct operational condition (FR-067). */
    List<StagedItem> findByReleaseState(ReleaseState releaseState);

    long countByReleaseState(ReleaseState releaseState);
}
