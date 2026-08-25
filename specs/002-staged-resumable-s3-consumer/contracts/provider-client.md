# Contract: Provider Client

The outbound half of the copy — what the delivery worker requires of the third-party provider. Three operations:
mint a download credential, fetch bytes (resumably), and signal that the source may be released.

Modelled as a Java interface so the whole feature is testable against `FakeProviderServer` without a real provider.

```java
public interface ProviderClient {
    ProviderCredential mintDownloadCredential(String recordingFileId);
    RecordingMetadata  fetchMetadata(String recordingFileId, ProviderCredential credential);
    ProviderDownload   openDownload(String recordingFileId, ProviderCredential credential, long fromByte);
    ReleaseOutcome     signalRelease(String recordingFileId);
}
```

---

## 1. `mintDownloadCredential`

Called at the **start of every delivery attempt** (FR-060) and again mid-transfer when the current credential's
remaining lifetime falls below `copy.provider.credential-renewal-margin` (FR-061).

| Property | Requirement |
|---|---|
| Input | The staged item's stable `recordingFileId` — never a credential captured at notification time (FR-059) |
| Output | `ProviderCredential(token, expiresAt)` |
| Persistence | None. Never written to the staging store, never logged at any level |
| Failure | `ProviderUnavailableException` (transient, retried) or `RecordingNotFoundException` (permanent) |

The retry window is deliberately **not** capped at credential lifetime (spec, Rejected alternatives): that would tie
how long the system keeps trying to an arbitrary provider parameter, so a provider outage longer than a token lifetime
would permanently fail items whose recordings are perfectly intact.

## 2. `fetchMetadata`

The fallback size lookup, used only when the accompanying size is absent or implausible (FR-048).

| Property | Requirement |
|---|---|
| Called | At most **once per staged item, across all retries** — the result is persisted as `resolved_size_bytes` (FR-049) |
| Output | `RecordingMetadata(sizeBytes, contentType, lastModified)` |
| Skipped entirely | When `x-recording-size` is present and plausible (FR-047, SC-010) |

## 3. `openDownload`

The resumable read leg.

```java
record ProviderDownload(
    InputStream body,
    long        firstByteOffset,   // 0 when the provider ignored the range
    long        totalSize,
    boolean     rangeHonoured
) implements Closeable {}
```

### Request

| Aspect | Requirement |
|---|---|
| Range | `Range: bytes={fromByte}-` whenever `fromByte > 0` (FR-044) |
| Scheme | `https` only |
| Host | Checked against `copy.provider.allowed-hosts` before connecting (FR-062) |
| Redirects | **Not followed automatically.** Each hop is re-checked against the allowlist — an allowlisted host that redirects inward would otherwise defeat the check (FR-062) |
| Body | Returned as an unbuffered `InputStream`; never materialised (FR-021, FR-022) |

### Response handling

| Status | Meaning | Action |
|---|---|---|
| `206 Partial Content` | Range honoured | `rangeHonoured=true`, stream starts at `fromByte` |
| `200 OK` | Range ignored — stream starts at byte 0 | `rangeHonoured=false`; the caller reads and **discards** the first `fromByte` bytes so the upload leg still resumes (FR-045, SC-002) |
| `401` / `403` | Credential expired or revoked | Re-mint and reopen at the derived resume position (FR-061) |
| `404` / `410` | Recording gone at the provider | `RecordingNotFoundException` — **permanent**, item fails terminally with the reason retained |
| `429`, `5xx` | Throttled or unavailable | `ProviderUnavailableException` — transient, retried with backoff |

The status code, not `Content-Range`, is the discriminator: a provider that ignores `Range` returns 200 with no
`Content-Range` header at all.

### Stream ends early

If the body ends before the expected size, the transfer fails **without finalizing** (FR-051). A truncated object is
never completed, so a release signal for it is unreachable.

## 4. `signalRelease`

The one irreversible call in the feature. The provider discards its copy in response.

| Property | Requirement |
|---|---|
| Precondition | The staged item is `DELIVERED` — object finalized **and** integrity-verified (FR-064, FR-065) |
| Idempotent | Repeat calls are safe; "already released" is **success**, not an error (FR-066) |
| Outcome recorded | `release_state`, `release_attempt_count`, `release_last_error` |
| On failure | `release_state = RELEASE_FAILED`, retried with backoff. **Never** reverts `delivery_state`, never re-copies (FR-068) |

```java
enum ReleaseOutcome { RELEASED, ALREADY_RELEASED, TRANSIENT_FAILURE, PERMANENT_FAILURE }
```

`ALREADY_RELEASED` exists as a distinct outcome because a crash between signalling and recording the outcome causes a
re-send, and the provider having already released the recording is exactly the state we wanted.

### Why the precondition is structural

The spec calls a premature release "the one unrecoverable failure in the feature" — the provider destroys its copy and
no complete copy exists anywhere. So the guard is not a check inside the release method; it is the shape of the data:
`release_state` can only leave `NOT_APPLICABLE` when `delivery_state` becomes `DELIVERED`, and `delivery_state` can only
become `DELIVERED` after all three verification layers pass. Being marked delivered is not sufficient on its own — a
finalization that produced a truncated object must not reach `DELIVERED` in the first place (SC-018).

---

## Failure classification

Provider failures are classified separately from object-store failures throughout, so an unhealthy provider cannot trip
the S3 circuit breaker or vice versa (FR-046).

| Exception | Circuit breaker | Retry |
|---|---|---|
| `ProviderUnavailableException` | `providerDownload` | Yes, with backoff |
| `RecordingNotFoundException` | none | No — permanent |
| `DisallowedProviderHostException` | none | No — permanent |
| `S3DeliveryUnavailableException` | `s3Delivery` | Yes, with backoff |

## Credentials

| Rule | Requirement |
|---|---|
| Read (provider) and write (object store) credentials are separate | FR-063 |
| Object-store write credentials are **never** sourced from message content or any provider-supplied field | FR-063 |
| The notification-verification secret comes from managed secret storage — never config files or code | FR-082 |
| No credential appears in a log line at any level | — |
