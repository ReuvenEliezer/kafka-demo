package com.reuven.kafka.demo.copy.staging;

/**
 * State machine for signalling the provider that it may discard its copy (data-model.md §1.3).
 * Modelled separately from {@link DeliveryState} precisely so that a release failure can never
 * revert delivery (FR-068) — it is structural rather than a rule to remember.
 */
public enum ReleaseState {
    /** Item not yet delivered. Release is impossible from here (FR-065). */
    NOT_APPLICABLE,
    /** Delivered and verified; release signal owed (FR-067). */
    PENDING,
    /** Provider acknowledged the release. Terminal. */
    RELEASED,
    /** Signal attempt failed; retried with backoff. Never reverts delivery (FR-068). */
    RELEASE_FAILED
}
