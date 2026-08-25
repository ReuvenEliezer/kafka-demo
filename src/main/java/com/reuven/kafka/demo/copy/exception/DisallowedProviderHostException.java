package com.reuven.kafka.demo.copy.exception;

/**
 * A download URL — the initial one or a redirect hop — resolved to a host outside
 * {@code copy.provider.allowed-hosts} (FR-062, R13). Fails the item permanently: an untrusted host
 * requires an operator decision, not a retry.
 */
public class DisallowedProviderHostException extends PermanentCopyException {

    public DisallowedProviderHostException(String message) {
        super(message);
    }
}
