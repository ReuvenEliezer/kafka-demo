# Contract: Transfer Checkpoint Store (Redis)

The resumption state for in-progress chunked uploads. Shared by all delivery workers, external to any worker's memory
(FR-027), and **disposable by design**.

> Losing this store costs re-transferred bytes and nothing else. It can never cost a lost recording, and it can never
> cause an incomplete object to be finalized. (FR-032, FR-033, SC-016)

## Key layout

```
xfer:{destinationBucket}:{destinationKey}      →  HASH
```

Keyed by the destination object's identity, so any worker can locate resumption state for a destination without
consulting the staging store first (FR-030).

## Hash fields

| Field | Type | Written | Notes |
|---|---|---|---|
| `uploadId` | string | at creation | S3 multipart upload id (FR-027) |
| `chunkSize` | int64 | at creation | Fixed for the upload's life. The multiplier in the derived resume position (FR-043) |
| `chunkCount` | int32 | at creation | Total expected parts |
| `totalSize` | int64 | at creation | Effective payload size the plan was built from |
| `createdAt` | int64 | at creation | Epoch millis, diagnostics only |
| `part:{n}` | string | per confirmation | `{etag}\|{crc32c}`, `n` 1-based (FR-028) |

`part:{n}` carries **both** the ordinal (in the field name) and the acknowledgement token (in the value) because
`CompleteMultipartUpload` requires `(partNumber, eTag)` pairs. A checkpoint holding ordinals alone would resume
correctly and then be unable to finalize — failure at the last step, after all the expensive work.

## Operations

### `create(bucket, key, uploadId, chunkSize, chunkCount, totalSize)`

`HSET` the metadata fields, then `EXPIRE` with the configured TTL. Issued immediately after
`CreateMultipartUpload` succeeds and before the first part is sent.

### `confirm(bucket, key, partNumber, etag, crc32c)` — **atomic**

```lua
-- KEYS[1] = xfer key
-- ARGV[1] = field name ("part:N"), ARGV[2] = "{etag}|{crc32c}", ARGV[3] = ttlSeconds
if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
redis.call('HSET',   KEYS[1], ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return 1
```

Executed as one `EVALSHA`. The atomicity is load-bearing, not tidiness: an `HSET` followed by a separate `EXPIRE`
leaves a window in which a crash between the two records a confirmed chunk under the *old*, shorter TTL — the exact
mid-flight expiry FR-038 exists to prevent.

The `EXISTS` guard makes the return value meaningful. `0` means the entry expired or was deleted beneath this transfer;
the caller must abandon the attempt and restart rather than continue against a checkpoint that no longer exists.

### `read(bucket, key)` → `Optional<TransferCheckpoint>`

`HGETALL`. Absent key → `Optional.empty()`, which means **restart the transfer**. It never means the transfer
completed (FR-032). Completion is decided solely by the staged item's `delivery_state` (FR-033).

### `delete(bucket, key)`

`DEL`, on finalization (FR-036) and when a checkpoint is found stale at S3 (FR-034).

## Derived resume position

Never stored (FR-043):

```
confirmedPrefixLength = largest k where part:1 … part:k all present
resumeBytePosition    = confirmedPrefixLength × chunkSize
nextPartNumber        = confirmedPrefixLength + 1
```

The **contiguous prefix**, not the field count. Sequential transfer (FR-042) should make gaps impossible; computing the
prefix means that if one ever appears, the cost is a re-transfer rather than a corrupt object that finalizes cleanly.

## TTL

| Property | Value |
|---|---|
| Source | `copy.checkpoint.expiry` |
| Set at | Creation and every confirmation (sliding, FR-038) |
| Lower bound | `> copy.delivery.max-attempts × copy.delivery.max-backoff` (FR-039) |
| Upper bound | `< copy.cleanup.abandoned-upload-retention` (FR-040) |
| Violation | Startup fails with a message naming both values and the relation (FR-041) |

Lower bound: a transfer still eligible for retry must still have its resumption state.
Upper bound: a checkpoint must never outlive the partial upload it references, or it would point at parts S3 has
already reclaimed.

## Failure modes

| Situation | Behaviour | Requirement |
|---|---|---|
| Entry missing at attempt start | Restart from part 1 | FR-032 |
| Entry present, `NoSuchUpload` at S3 | Abort, delete entry, restart from part 1 | FR-034 |
| Redis unreachable at attempt start | Fail the attempt as transient; retry with backoff. Do **not** start an unresumable chunked upload | FR-058 |
| Redis unreachable mid-transfer | `confirm` fails; abandon the attempt. Confirmed parts stay at S3; the next attempt restarts from whatever prefix survived | SC-016 |
| Entire store flushed | Every affected transfer restarts. No object finalized incomplete, no recording lost | SC-016 |
| Contiguity gap observed | Resume from the prefix; later parts are re-uploaded and overwritten | FR-037 |

Redis unavailability is surfaced as a health-indicator condition and a metric (FR-058), because although it costs only
re-transferred bytes, sustained unavailability turns every large transfer into a non-resumable one.

## What is *not* stored here

| Not stored | Where it lives | Why |
|---|---|---|
| Whether the item was delivered | `staged_item.delivery_state` | FR-033 — checkpoint absence must never read as completion |
| The read offset | Derived (above) | FR-043 — a second source of truth can drift and silently corrupt |
| Provider credentials | Nowhere; minted per attempt | FR-059, FR-060 |
| Anything for a below-threshold payload | Nothing is created | FR-024 |
