package com.reuven.kafka.demo.copy.message;

/**
 * Kafka header names for {@link RecordingCopyMessage} (contracts/recording-copy-message.md).
 */
public final class CopyMessageHeaders {

    /** Declared size, duplicated from the body so the delivery worker can pick an upload path without deserialising (FR-075). */
    public static final String RECORDING_SIZE = "x-recording-size";

    /** Notification identifier; makes provider-retry duplicates detectable downstream (FR-076). */
    public static final String PROVIDER_EVENT_ID = "x-provider-event-id";

    /** Provider tenant, for routing and metrics. */
    public static final String PROVIDER_ACCOUNT_ID = "x-provider-account-id";

    private CopyMessageHeaders() {
    }
}
