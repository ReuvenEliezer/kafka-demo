package com.reuven.kafka.demo.copy.staging;

/**
 * State machine for a {@link StagedItem}'s delivery to the destination object store
 * (data-model.md §1.2). Only {@code DELIVERY_IN_PROGRESS -> DELIVERED} may be taken, and only after
 * all three verification layers pass (R14) — there is no path that marks an item delivered on the
 * strength of a missing checkpoint (FR-032).
 */
public enum DeliveryState {
    /** Durably staged, not yet claimed. Counts toward the backlog (FR-011). */
    AWAITING_DELIVERY,
    /** Claimed; a transfer may be running. Still counts toward the backlog. */
    DELIVERY_IN_PROGRESS,
    /** Object finalized and verified. The only state from which release may be signalled (FR-065). Terminal. */
    DELIVERED,
    /** Attempts exhausted or an unrecoverable error. Excluded from the claim query (SC-015). Terminal. */
    PERMANENTLY_FAILED
}
