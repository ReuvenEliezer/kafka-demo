package com.reuven.kafka.demo.copy.config;

/**
 * The two consumption strategies this repository carries side by side, selected by the single
 * {@code copy.consumer.strategy} property (FR-003).
 */
public enum ConsumptionStrategy {
    INLINE,
    STAGED
}
