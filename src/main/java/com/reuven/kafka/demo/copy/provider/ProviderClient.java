package com.reuven.kafka.demo.copy.provider;

import com.reuven.kafka.demo.copy.exception.DisallowedProviderHostException;
import com.reuven.kafka.demo.copy.exception.ProviderUnavailableException;
import com.reuven.kafka.demo.copy.exception.RecordingNotFoundException;

/**
 * The outbound half of the copy (contracts/provider-client.md). Modelled as an interface so the
 * whole feature is testable against {@code FakeProviderServer} without a real provider.
 */
public interface ProviderClient {

    /**
     * Mints a fresh download credential from the recording's stable identifier — never from a
     * credential captured at notification time (FR-059, FR-060). Called at the start of every
     * attempt and again mid-transfer when the current credential's remaining lifetime falls below
     * {@code copy.provider.credential-renewal-margin} (FR-061).
     *
     * @throws ProviderUnavailableException transient
     * @throws RecordingNotFoundException   permanent
     */
    ProviderCredential mintDownloadCredential(String recordingFileId);

    /**
     * The fallback size lookup (FR-048). Callers must invoke this at most once per staged item
     * across all retries and persist the result (FR-049).
     */
    RecordingMetadata fetchMetadata(String recordingFileId, ProviderCredential credential);

    /**
     * Opens the resumable read leg at {@code fromByte}, checked against
     * {@code copy.provider.allowed-hosts} on every hop (FR-062).
     *
     * @throws DisallowedProviderHostException permanent
     * @throws RecordingNotFoundException      permanent (404/410)
     * @throws ProviderUnavailableException    transient (429/5xx, or an expired credential the
     *                                         caller should renew and retry)
     */
    ProviderDownload openDownload(String recordingFileId, ProviderCredential credential, long fromByte);

    /**
     * The one irreversible call in the feature. Only ever invoked once the staged item is
     * {@code DELIVERED} (FR-064, FR-065) — a precondition enforced structurally by the caller, not
     * by this method.
     */
    ReleaseOutcome signalRelease(String recordingFileId);
}
