# Contract: Configuration Surface

Every knob the feature adds, its default, and — where the spec demands it — the startup check that rejects an invalid
combination before the application accepts a single message.

Bound to a single `@ConfigurationProperties("copy")` root record. Cross-field relations are validated in that record's
compact constructor, so an invalid combination fails binding and the context never starts (FR-041).

## `copy.consumer`

| Key | Type | Default | Notes |
|---|---|---|---|
| `strategy` | `inline \| staged` | `inline` | The single knob selecting a consumption strategy (FR-003). Also activates the matching Spring profile, so the inactive strategy's infrastructure is not auto-configured at all (FR-004) |
| `topic` | String | `recording-copy` | Topic the staged strategy consumes |
| `group-id` | String | `recording-copy-group` | |
| `batch.max-records` | int | `100` | Batch size bound (FR-006) |
| `batch.max-wait` | Duration | `1s` | Batch time bound (FR-006) |

## `copy.backlog`

| Key | Type | Default | Notes |
|---|---|---|---|
| `high-water-mark` | int | `10000` | Consumption pauses at or above this (FR-012) |
| `low-water-mark` | int | `5000` | Consumption resumes below this. Hysteresis prevents flapping |
| `check-interval` | Duration | `5s` | Governor poll interval |

**Validated**: `low-water-mark < high-water-mark`.

## `copy.delivery`

| Key | Type | Default | Notes |
|---|---|---|---|
| `worker-concurrency` | int | `4` | Concurrent items, not concurrent chunks — chunk parallelism is out of scope |
| `poll-interval` | Duration | `2s` | Claim-query interval |
| `max-attempts` | int | `10` | Then `PERMANENTLY_FAILED` (FR-019) |
| `initial-backoff` | Duration | `10s` | |
| `max-backoff` | Duration | `30m` | Backoff ceiling |
| `claim-timeout` | Duration | `5m` | Claim expiry; heartbeat-extended on every chunk confirmation (FR-017) |

**Derived**: `maxRetrySpan = max-attempts × max-backoff` (default: 10 × 30m = **5h**). This is the quantity the
checkpoint expiry and cleanup retention are both ordered against.

## `copy.chunking`

| Key | Type | Default | Notes |
|---|---|---|---|
| `threshold` | DataSize | `100MB` | At or above → chunked **and** checkpointed; below → single request, no checkpoint. One threshold governs both (FR-023, FR-024) |
| `base-part-size` | DataSize | `16MB` | Floor for derived part size (R4) |

**Validated**: `threshold <= 5GiB`, the S3 single-request maximum (FR-025). A threshold above it would route payloads
into a path physically unable to carry them.

> The spec's originating description suggested a 10 GB threshold. It cannot be honoured: every payload between 5 GiB and
> 10 GB would take the single-request path and fail. Hence the cap, checked at startup rather than discovered in
> production.

## `copy.checkpoint`

| Key | Type | Default | Notes |
|---|---|---|---|
| `expiry` | Duration | `24h` | Sliding TTL, refreshed on every confirmation (FR-038) |
| `key-prefix` | String | `xfer` | |

**Validated** (FR-039, FR-040, FR-041):

```
maxRetrySpan  <  copy.checkpoint.expiry  <  copy.cleanup.abandoned-upload-retention
   5h                    24h                          7d              (defaults)
```

Lower bound — an item still eligible for retry must still have resumption state.
Upper bound — a checkpoint must never outlive the partial upload it references.

Failure message names both operands and the relation, e.g.:

```
copy.checkpoint.expiry (24h) must exceed the maximum retry span
(copy.delivery.max-attempts 10 x copy.delivery.max-backoff 30m = 5h)
```

## `copy.cleanup`

| Key | Type | Default | Notes |
|---|---|---|---|
| `abandoned-upload-retention` | Duration | `7d` | Multipart uploads older than this are aborted (FR-055). Strictly longer than `maxRetrySpan`, so a retryable transfer can never be reaped (FR-056) |
| `scan-interval` | Duration | `1h` | Reaper interval; also refreshes the unfinished-transfer gauges (FR-057) |

## `copy.size`

| Key | Type | Default | Notes |
|---|---|---|---|
| `max-plausible-bytes` | DataSize | `5TB` | Declared sizes above this are rejected as implausible and fall back to a metadata lookup (FR-048) |

## `copy.provider`

| Key | Type | Default | Notes |
|---|---|---|---|
| `base-url` | String | — | Required when the staged strategy is active |
| `allowed-hosts` | List\<String\> | — | Download-target allowlist, checked per redirect hop (FR-062) |
| `credential-renewal-margin` | Duration | `5m` | Re-mint when remaining lifetime drops below this (FR-061) |
| `connect-timeout` | Duration | `10s` | |
| `read-timeout` | Duration | `60s` | Per read, not per transfer — a multi-hour transfer must not time out |

**Validated**: `allowed-hosts` non-empty when the staged strategy is active. An empty allowlist that silently permitted
everything would invert FR-062.

## `copy.notification`

| Key | Type | Default | Notes |
|---|---|---|---|
| `path` | String | `/provider/notifications` | |
| `secret` | String | — | **From environment only** (`COPY_NOTIFICATION_SECRET`). Never in a config file (FR-082) |
| `freshness-window` | Duration | `5m` | Signed-timestamp range check (FR-072) |
| `max-body-size` | DataSize | `1MB` | Bounds the raw body cached for signature verification |

**Validated**: `secret` present and at least 32 characters when the endpoint is enabled.

## `copy.destination`

| Key | Type | Default | Notes |
|---|---|---|---|
| `bucket` | String | `${aws.s3.bucket}` | |
| `key-prefix` | String | `recordings` | Destination key is `{prefix}/{accountId}/{sessionId}/{recordingFileId}` (FR-052) |

## Resilience4j instances

| Instance | Guards | Introduced by |
|---|---|---|
| `s3Upload` | Inline strategy's S3 call — **unchanged**, still drives consumer pause/resume | existing (feature 001) |
| `s3Delivery` | Staged delivery worker's S3 calls. Does **not** pause the consumer (FR-020, SC-004) | this feature |
| `providerDownload` | Provider fetch and metadata calls. Separate from S3 so neither trips the other (FR-046) | this feature |

## Startup validation summary

| # | Check | Requirement |
|---|---|---|
| V1 | `chunking.threshold <= 5GiB` | FR-025 |
| V2 | `checkpoint.expiry > maxAttempts × maxBackoff` | FR-039 |
| V3 | `checkpoint.expiry < cleanup.abandoned-upload-retention` | FR-040 |
| V4 | `backlog.low-water-mark < backlog.high-water-mark` | FR-012 |
| V5 | `provider.allowed-hosts` non-empty (staged) | FR-062 |
| V6 | `notification.secret` present, >= 32 chars | FR-082 |

All six fail the context with a message naming the offending keys and their values (FR-041).
