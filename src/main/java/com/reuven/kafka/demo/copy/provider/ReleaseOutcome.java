package com.reuven.kafka.demo.copy.provider;

/**
 * Result of {@link ProviderClient#signalRelease}. {@link #ALREADY_RELEASED} is a distinct success —
 * a crash between signalling and recording the outcome causes a harmless re-send, and the provider
 * having already released is exactly the state that was wanted (FR-066).
 */
public enum ReleaseOutcome {
    RELEASED,
    ALREADY_RELEASED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE
}
