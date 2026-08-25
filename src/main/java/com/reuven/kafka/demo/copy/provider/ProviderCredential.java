package com.reuven.kafka.demo.copy.provider;

import java.time.Instant;

/**
 * A short-lived, provider-issued download credential. Never persisted, never logged (FR-059).
 */
public record ProviderCredential(
        String token,
        Instant expiresAt
) {
}
