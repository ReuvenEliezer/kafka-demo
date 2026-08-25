# Phase 1 Data Model: Staged Consumer with Resumable Chunked Object Upload

**Feature**: `002-staged-resumable-s3-consumer` | **Date**: 2026-08-24

Entities are grouped by the store that owns them. The central invariant of the whole feature is the split between the
two stores:

> **The staged item is authoritative for "does this still need delivering". The transfer checkpoint is authoritative for
> nothing — it is an optimisation whose absence means "restart".** (FR-032, FR-033)

Every design choice below follows from that sentence.

---

## 1. PostgreSQL — durable staging (authoritative)

### 1.1 `staged_item`

One row per recording *file* to copy. Created by the staged consumer inside the batch transaction, mutated by the
delivery worker, read by the backlog governor and the metrics gauges.

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `bigserial` PK | no | Surrogate key |
| `recording_file_id` | `text` | no | Provider's stable identifier for **this file**. Natural key (FR-059) |
| `session_id` | `text` | no | Provider's identifier for the session the file belongs to |
| `provider_account_id` | `text` | no | Provider tenant, part of the destination key |
| `provider_event_id` | `text` | no | Notification identifier, for duplicate detection (FR-076) |
| `destination_bucket` | `text` | no | Resolved at staging time |
| `destination_key` | `text` | no | Deterministic (FR-052, R15); unique |
| `declared_size_bytes` | `bigint` | yes | From message metadata when present (FR-047) |
| `resolved_size_bytes` | `bigint` | yes | Persisted result of a metadata lookup; written at most once (FR-049) |
| `content_type` | `text` | yes | Provider-declared |
| `delivery_state` | `text` | no | See §1.2. Default `AWAITING_DELIVERY` |
| `attempt_count` | `int` | no | Default `0` (FR-018) |
| `next_attempt_at` | `timestamptz` | no | Default `now()`; retry schedule (FR-018) |
| `last_failure_reason` | `text` | yes | Retained on terminal failure (FR-019) |
| `last_failure_at` | `timestamptz` | yes | |
| `claim_owner` | `text` | yes | Worker instance id holding the claim (FR-016) |
| `claim_expires_at` | `timestamptz` | yes | Heartbeat-extended; a passed expiry is reclaimable (FR-017) |
| `release_state` | `text` | no | See §1.3. Default `NOT_APPLICABLE` |
| `release_attempt_count` | `int` | no | Default `0` |
| `release_last_error` | `text` | yes | |
| `verified_checksum` | `text` | yes | Full-object CRC32C recorded at verification (R14) |
| `verified_size_bytes` | `bigint` | yes | `HeadObject` content length at verification |
| `delivered_at` | `timestamptz` | yes | |
| `created_at` | `timestamptz` | no | Default `now()` |
| `updated_at` | `timestamptz` | no | |

**Constraints and indexes**

| Name | Definition | Why |
|---|---|---|
| `uq_staged_item_file` | `UNIQUE (recording_file_id)` | Makes redelivery of a batch an idempotent no-op via `ON CONFLICT DO NOTHING`, satisfying at-least-once without duplicate rows |
| `uq_staged_item_destination` | `UNIQUE (destination_bucket, destination_key)` | Guards the FR-052 naming derivation — a collision is a bug, and this makes it fail loudly at staging rather than silently overwrite later |
| `ix_staged_item_claimable` | `(next_attempt_at) WHERE delivery_state = 'AWAITING_DELIVERY'` | The worker's claim query; partial index keeps it small as delivered rows accumulate |
| `ix_staged_item_stale_claim` | `(claim_expires_at) WHERE delivery_state = 'DELIVERY_IN_PROGRESS'` | The claim reaper's scan (FR-017) |
| `ix_staged_item_release_pending` | `(delivered_at) WHERE release_state = 'PENDING'` | Surfaces delivered-but-unreleased items as a distinct condition (FR-067) |
| `ix_staged_item_backlog` | `(delivery_state)` | Backlog count gauge (FR-011) |

**Size fields, and why there are two.** `declared_size_bytes` is a *hint* from the provider that may be absent,
implausible, or simply wrong (FR-048, FR-050). `resolved_size_bytes` is a fact obtained by a metadata lookup. Keeping
them apart is what lets FR-049 hold — the lookup is performed once and its result persisted, so retries days later do
not re-probe — while still recording that the original hint disagreed. The effective size used by the chunk planner is
`coalesce(resolved_size_bytes, declared_size_bytes)`.

### 1.2 `delivery_state` — states and transitions

```
                    ┌───────────────────────┐
   staged  ───────► │  AWAITING_DELIVERY    │ ◄──────────────┐
                    └───────────┬───────────┘                │
                                │ claim (SKIP LOCKED)        │ release claim:
                                ▼                            │  - transient failure, attempts remain
                    ┌───────────────────────┐                │  - claim expired (crashed worker)
                    │ DELIVERY_IN_PROGRESS  │ ───────────────┘
                    └───────────┬───────────┘
                verified ok     │      attempts exhausted, or permanent error
                    ┌───────────┴───────────┐
                    ▼                       ▼
            ┌───────────────┐      ┌────────────────────┐
            │   DELIVERED   │      │ PERMANENTLY_FAILED │
            └───────┬───────┘      └────────────────────┘
                    │ (terminal for delivery; release proceeds separately — §1.3)
```

| State | Meaning | Exit |
|---|---|---|
| `AWAITING_DELIVERY` | Durably staged, not yet claimed. Counts toward backlog (FR-011) | Claimed by a worker |
| `DELIVERY_IN_PROGRESS` | Claimed; a transfer may be running. Still counts toward backlog | Verified → `DELIVERED`; recoverable failure → back to `AWAITING_DELIVERY` with backoff; exhausted or permanent → `PERMANENTLY_FAILED` |
| `DELIVERED` | Object finalized **and verified** (all three layers of R14). The only state from which release may be signalled (FR-065) | Terminal |
| `PERMANENTLY_FAILED` | Attempts exhausted or an unrecoverable error. Reason retained (FR-019). Excluded from the claim query so it blocks nothing (SC-015) | Terminal (manual requeue only) |

**Transition rules**

- Only `DELIVERY_IN_PROGRESS → DELIVERED` may be taken, and only after all three verification layers pass. There is no
  path that marks an item delivered on the strength of "the checkpoint is gone" — that reading is the failure mode
  FR-032 exists to forbid.
- `DELIVERY_IN_PROGRESS → AWAITING_DELIVERY` clears `claim_owner`/`claim_expires_at`, increments `attempt_count`, and
  sets `next_attempt_at = now() + backoff(attempt_count)`. It does **not** touch the checkpoint: the surviving
  checkpoint is what makes the next attempt resumable.
- Reclaiming an expired claim uses the same transition, driven by the reaper rather than by the worker.
- `DELIVERED` and `PERMANENTLY_FAILED` are terminal for delivery. A release-signal failure never reverts `DELIVERED`
  (FR-068).

### 1.3 `release_state` — states and transitions

Modelled separately from `delivery_state` precisely so that FR-068 is structural rather than a rule someone must
remember: no release outcome can express itself as a delivery-state change.

```
NOT_APPLICABLE ──(item reaches DELIVERED)──► PENDING ──► RELEASED
                                               │
                                               └──► RELEASE_FAILED ──(retry)──► PENDING
```

| State | Meaning |
|---|---|
| `NOT_APPLICABLE` | Item not yet delivered. Release is impossible from here (FR-065) |
| `PENDING` | Delivered and verified; release signal owed. Surfaced as a distinct operational condition (FR-067) |
| `RELEASED` | Provider acknowledged the release. Terminal |
| `RELEASE_FAILED` | Signal attempt failed; retried with backoff. Never reverts delivery (FR-068) |

The signal is idempotent at the provider (FR-066), so a crash between signalling and recording the outcome causes a
harmless re-send, and "already released" is treated as success rather than as an error.

### 1.4 Backlog State *(derived, not stored)*

The spec's *Backlog State* entity is a projection, not a table:

```sql
SELECT count(*) FROM staged_item
 WHERE delivery_state IN ('AWAITING_DELIVERY', 'DELIVERY_IN_PROGRESS');
```

Held in memory as a Micrometer gauge refreshed by the backlog governor, alongside the age of the oldest undelivered
item. Storing it would create a second thing to keep consistent with the rows it summarises.

---

## 2. Redis — transfer checkpoints (disposable)

### 2.1 `TransferCheckpoint`

One hash per destination object. Keyed by the destination's identity so any worker can find it without consulting
another system (FR-030).

**Key**: `xfer:{destination_bucket}:{destination_key}`

| Field | Type | Notes |
|---|---|---|
| `uploadId` | string | S3 multipart upload identifier (FR-027) |
| `chunkSize` | long | Fixed for the life of the upload; the multiplier in FR-043's derived resume position |
| `chunkCount` | int | Total expected parts |
| `totalSize` | long | Effective payload size the plan was built from |
| `createdAt` | epoch millis | Diagnostics only |
| `part:{n}` | string | One field per confirmed chunk, `n` 1-based. Value is `{etag}\|{crc32c}` — see §2.2 |

**TTL**: set on creation and **refreshed on every chunk confirmation** (FR-038), in the same atomic Lua call as the
field write (R2). A transfer that is making progress can therefore never have its resumption state expire beneath it,
however long it runs (SC-017).

**Lifecycle**

| Event | Effect |
|---|---|
| Chunked transfer begins, no entry exists | `CreateMultipartUpload`, then write the entry |
| Chunk `n` confirmed by S3 | Atomically `HSET part:{n}` and refresh TTL; extend the staged item's claim |
| Entry exists, upload still valid at S3 | Resume from the contiguous prefix |
| Entry exists, `NoSuchUpload` at S3 | Abort, delete the entry, restart from chunk 1 (FR-034) |
| Entry missing or expired | Restart from chunk 1. **Never** interpreted as completion (FR-032) |
| Upload finalized | Delete the entry (FR-036) |
| Payload below threshold | No entry is ever created (FR-024) |

### 2.2 `ChunkConfirmation`

A value type, stored as one field of the hash rather than as an entity of its own.

| Component | Notes |
|---|---|
| `partNumber` | 1-based ordinal (FR-028) |
| `etag` | The token S3 returned for the part. **Required by `CompleteMultipartUpload`** |
| `crc32c` | Per-part checksum, for the verification chain (R14) |

FR-028 is emphatic that an ordinal without its token is not a usable confirmation, and this is the reason: the
finalization call takes a list of `(partNumber, eTag)` pairs. A checkpoint recording only ordinals would let a transfer
resume correctly and then be unable to finish — the worst possible failure shape, because it appears to work until the
last step.

### 2.3 `ResumePosition` *(derived, never stored)*

```
confirmedPrefixLength = largest k such that part:1 … part:k all exist
resumeBytePosition    = confirmedPrefixLength × chunkSize
nextPartNumber        = confirmedPrefixLength + 1
```

Exact because chunks are transferred sequentially (FR-042), so confirmed chunks always form a contiguous prefix. FR-043
forbids storing this as a field, and the reason is worth restating: a stored read offset is a second source of truth
that can drift from the chunk record, and a drifted offset produces a *corrupt object that finalizes successfully* —
undetectable without a full re-read.

Computing the **contiguous prefix** rather than the count of present fields matters for the same reason. If the hash
somehow held `part:1, part:2, part:4`, the count is 3 but the safe resume point is after part 2. Sequential transfer
should make gaps impossible; the prefix computation means a gap costs a re-transfer rather than corruption.

---

## 3. Kafka — the copy message

### 3.1 `RecordingCopyMessage` (value)

One message per recording *file* (FR-077). Carries a reference, never bytes.

| Field | Type | Notes |
|---|---|---|
| `recordingFileId` | `String` | Provider's stable identifier for the file (FR-059) |
| `sessionId` | `String` | Session the file belongs to |
| `providerAccountId` | `String` | Provider tenant |
| `fileType` | `String` | `VIDEO` / `AUDIO` / `TRANSCRIPT` / `CHAT` — descriptive only |
| `downloadUrl` | `String` | Provider-hosted location. Host-allowlisted before use (FR-062) |
| `declaredSizeBytes` | `Long` | Nullable hint (FR-047, FR-048) |
| `contentType` | `String` | Nullable |
| `recordingEndedAt` | `Instant` | Provider-supplied |

**Headers** (FR-075, FR-076) — see [contracts/recording-copy-message.md](./contracts/recording-copy-message.md) for the
authoritative definition.

**No download credential is carried in the message or persisted on the staged item.** A credential captured at
notification time would be long expired by the time a multi-day retry span elapsed (FR-059, FR-060), so it is minted
fresh per attempt from `recordingFileId`.

### 3.2 Relationship to the existing `MyEvent`

`MyEvent` is untouched. The inline strategy continues to consume it from the existing topic. The staged strategy
consumes `RecordingCopyMessage` from its own topic.

**The two strategies do not, and cannot, produce identical objects.** The inline path writes `{id}.json` containing a
serialisation of the event itself; the staged path copies provider-hosted bytes to
`recordings/{accountId}/{sessionId}/{recordingFileId}`. They carry different work, and the spec's scope boundary
forbids changing the inline path to close that gap. FR-005 and SC-012 are therefore satisfied by *behavioural*
comparability under identical conditions — acknowledgement timing, backlog accumulation, and pause semantics — not by
object equality. `ObjectKeyResolver` is used by the staged path only.

---

## 4. Provider-facing values *(transient, never persisted)*

| Value | Fields | Lifetime |
|---|---|---|
| `ProviderCredential` | `token`, `expiresAt` | One attempt; renewed at chunk boundaries when its margin runs out (FR-061). Never persisted, never logged |
| `ProviderNotification` | `eventId`, `eventType`, `signedTimestamp`, `accountId`, `sessionId`, `files[]` | Request scope only |
| `NotificationFile` | `recordingFileId`, `fileType`, `downloadUrl`, `sizeBytes`, `contentType` | One per published message (FR-077) |

---

## 5. Cross-store invariants

These are the properties the whole feature rests on. Each names the test that proves it.

| # | Invariant | Enforced by | Proven by |
|---|---|---|---|
| I1 | An acknowledged message has a committed `staged_item` row | Ack strictly after transaction commit (R8) | SC-006 crash-injection test |
| I2 | Absence of a checkpoint never implies delivery | `DELIVERED` is reachable only from verified completion | SC-016 checkpoint-flush test |
| I3 | Confirmed chunks form a contiguous prefix | Sequential transfer (FR-042) + prefix computation (§2.3) | SC-001 resume test |
| I4 | A checkpoint entry exists only for chunked transfers | Single threshold governs both (FR-024) | SC-009 below-threshold test |
| I5 | No two workers transfer the same item concurrently | `SKIP LOCKED` claim + heartbeat expiry (R18) | Concurrent-worker test |
| I6 | Release is signalled only for a verified `DELIVERED` item | `release_state` machine gated on `delivery_state` | SC-018 fault-injection test |
| I7 | A release failure never reverts delivery | Separate state machines (§1.3) | SC-018 |
| I8 | Checkpoint TTL exceeds the maximum retry span | Startup validation (R5) | Startup-validation test |
| I9 | A permanently failed item blocks nothing | Excluded from the claim query | SC-015 poison-payload test |
| I10 | A finalized object is byte-identical to the source | Three-layer verification (R14) | SC-007 across three size bands |
