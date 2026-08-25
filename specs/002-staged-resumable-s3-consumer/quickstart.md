# Quickstart & Validation Guide

**Feature**: `002-staged-resumable-s3-consumer`

How to run each strategy and how to prove the feature works. Every scenario maps to a Success Criterion from
[spec.md](./spec.md); running them all is the acceptance evidence for the feature.

This is a run/validate guide. Implementation belongs in `tasks.md`.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| JDK 21 | Matches the CI pin. A newer JDK works but `maven.compiler.release` stays at 21 |
| Docker | Kafka, PostgreSQL, Redis, LocalStack |
| Maven | Not on `PATH` in the current shell — use an absolute path or your IDE's bundled Maven |

## Infrastructure

`docker-compose.yml` gains PostgreSQL and Redis alongside the existing Kafka service, each with a health check so
`--wait` blocks until they are genuinely ready (the CI workflow already relies on that).

```bash
docker compose up -d --wait
```

| Service | Port | Purpose |
|---|---|---|
| kafka | 29092 | Existing broker |
| postgres | 5432 | Staging store — authoritative |
| redis | 6379 | Checkpoint store — disposable |

LocalStack is **not** in compose; S3 comes from `LocalStackContainer` in tests, matching how feature 001 already works.

---

## Running each strategy

One knob selects the strategy (FR-003). The inactive strategy's infrastructure is not auto-configured at all, so inline
mode needs neither PostgreSQL nor Redis (FR-004).

**Inline** — the existing behaviour, unchanged, as the comparison baseline:

```bash
java -jar target/kafka-demo-0.0.1-SNAPSHOT.jar --copy.consumer.strategy=inline
```

**Staged** — batched intake, durable staging, asynchronous chunked delivery:

```bash
java -jar target/kafka-demo-0.0.1-SNAPSHOT.jar --copy.consumer.strategy=staged
```

Switching is a restart with a different value and no code change (SC-011). Config surface:
[contracts/configuration.md](./contracts/configuration.md).

### Test-profile sizing

Production defaults (100 MB threshold, 16 MB parts) would make every test move gigabytes. `src/test/resources/application.yaml`
lowers them so multi-chunk behaviour is exercised in seconds:

| Key | Prod default | Test |
|---|---|---|
| `copy.chunking.threshold` | `100MB` | `5MB` |
| `copy.chunking.base-part-size` | `16MB` | `5MB` |
| `copy.checkpoint.expiry` | `24h` | `60s` |
| `copy.delivery.max-attempts` | `10` | `3` |
| `copy.delivery.max-backoff` | `30m` | `2s` |

The ordering constraints (V2, V3) still hold at these values — the point is that they are checked, not that they are
large.

---

## Validation scenarios

### S1 — Resume after interruption *(SC-001, SC-003)*

The central scenario. `ResumableUploadIntegrationTest`.

1. Stage one item whose payload is ~10 parts.
2. Let the transfer confirm ~9 parts, then kill S3 connectivity mid-flight.
3. **Restart the Spring context** — not merely an in-process retry, which is what makes this SC-003 and not just SC-001.
4. Restore connectivity and let the retry run.

**Expect**: the retry requests the provider from `confirmedParts × partSize`, uploads only the missing parts, finalizes
an object byte-identical to the source, and deletes the checkpoint entry.

**Measure**: `FakeProviderServer` counts bytes served and the LocalStack request log counts `UploadPart` calls. Both
should be ~10% of the payload — the "both legs" half of SC-001 is what distinguishes this from a naive resume that
re-downloads everything.

### S2 — Provider ignores the range header *(SC-002)*

Same as S1, but `FakeProviderServer` is set to ignore `Range` and serve 200 from byte 0.

**Expect**: ~100% re-downloaded, still only ~10% uploaded. The expensive leg is still saved. This is the branch on
`206` vs `200` (R11) — the test exists because that branch is easy to omit and invisible until a real provider misbehaves.

### S3 — Intake survives an S3 outage *(SC-004, SC-005)*

`StagedConsumerIntegrationTest`.

1. Stop LocalStack.
2. Publish a steady stream of messages.

**Expect**: consumption and acknowledgement continue at full rate; consumer-group lag does not grow; the backlog gauge
climbs. Restart LocalStack: the backlog drains to zero with no intervention.

**Contrast**: run the same scenario with `strategy=inline` and observe the consumer pause and lag grow. That contrast is
the feature's whole justification and is worth capturing in the test output.

### S4 — No message loss across crashes *(SC-006)*

Inject failures at each boundary: after staging before ack; after ack before claim; mid-transfer; after finalization
before the state write.

**Expect**: every acknowledged message is present in the staging store or the object store — never absent from both.
Duplicates are permitted and land on the same key (at-least-once, FR-052).

### S5 — Byte-identity across size bands *(SC-007, SC-008)*

Three payloads spanning >3 orders of magnitude: **1 KiB** (below threshold), **6 MiB** (just above), **60 MiB** (many
parts).

**Expect**: each stored object is byte-identical to the source, verified by CRC32C over the streamed bytes.

**Memory**: sample heap during each transfer; peak attributable to one transfer must stay under 32 MB, and the
60 MiB run's peak must be within 2x the 1 KiB run's. This is what R3's
pass-through streaming buys, and the test is the only thing that will catch a regression into buffering a whole part.

### S6 — Below-threshold payloads pay nothing *(SC-009, SC-010)*

**Expect**: for a 1 KiB payload — zero Redis keys created, one `PutObject`, no `CreateMultipartUpload`, and zero
provider metadata calls when `x-recording-size` is present and plausible.

### S7 — Strategy comparison *(SC-011, SC-012)*

Run each strategy in turn under an identical induced S3 outage and recovery.

**Expect**: the contrast is visible and measurable — inline pauses its consumer and topic lag grows; staged keeps
consuming at full rate and its staged backlog grows instead. Both deliver everything they acknowledged.

**Not** identical objects: the strategies carry different work, and the spec preserves the inline path unchanged.
That contrast *is* the comparison the feature exists to demonstrate.

### S8 — Backlog pause causes no rebalance *(SC-013)*

Drive the backlog above the high-water mark.

**Expect**: `container.isContainerPaused()` is true; consumption stops; the consumer stays in its group and the
generation id does not change. Below the low-water mark it resumes. A rebalance here would be a silent regression —
assert on the generation, not just on the pause flag.

### S9 — Checkpoint expiry slides with progress *(SC-017)*

Set `copy.checkpoint.expiry=10s` and run a transfer that takes ~60s, confirming a part every few seconds.

**Expect**: the transfer completes. The TTL never lapses because every confirmation refreshes it (FR-038). Without the
sliding refresh this test fails; with a non-atomic `HSET` + `EXPIRE` it fails *intermittently*, which is the argument
for the Lua script (R2).

### S10 — Flushing the checkpoint store costs only bytes *(SC-016)*

`FLUSHALL` mid-transfer.

**Expect**: affected transfers restart from part 1 and still complete correctly. **Critically**: no object is finalized
incomplete and no acknowledged recording goes missing. Assert the *dangerous* misreading is absent — that no item
reached `DELIVERED` on the strength of a missing checkpoint (I2, FR-032).

### S11 — No premature release *(SC-018)*

Fault-inject at mid-chunk, before finalization, and during finalization.

**Expect**: `FakeProviderServer` records **zero** release signals for any recording whose object is not finalized and
verified. This is the feature's one unrecoverable failure, so the assertion is a hard zero, not a rate.

### S12 — Credential outlives nothing *(SC-019, SC-020)*

Configure `FakeProviderServer` to expire credentials after ~30% of a payload; separately, retry an item after the
original notification's credential would have expired.

**Expect**: both complete. The first renews at a chunk boundary and continues from the derived resume position rather
than restarting (FR-061); the second proves the staged item never depended on a captured credential (FR-059).

### S13 — Notification ingress *(SC-021, SC-022, SC-023)*

`NotificationIngressTest`.

| Case | Expect |
|---|---|
| Valid signature, 3 recording files | 200, exactly 3 messages published |
| Missing / wrong / stale signature | 401 or 408, **nothing** published |
| Timestamp outside freshness window | 408, nothing published |
| `endpoint.url_validation` | 200 with correct `encryptedToken` |
| Broker unavailable | 503 (retryable), nothing acknowledged as delivered |
| Same notification delivered twice | Exactly one stored object, exactly one release signal |
| Latency at 50 notifications/sec sustained | p99 under 3s (SC-022) |

### S14 — Startup validation *(FR-025, FR-039, FR-040, FR-041)*

Six context-fails-to-start cases, one per check in
[contracts/configuration.md](./contracts/configuration.md#startup-validation-summary).

**Expect**: context refresh fails, and the message names the offending keys and their values. Assert on the message
content — a check that fails with an opaque error is barely better than no check.

### S15 — Abandoned uploads are reclaimed *(SC-014, SC-015, FR-057)*

**Expect**: uploads past the retention window are aborted and reported; uploads still within a retry span are **not**
(FR-056); a permanently failed item does not delay unrelated items behind it (SC-015).

---

## Running the tests

```bash
mvn clean install
```

Testcontainers starts Kafka, PostgreSQL, Redis, and LocalStack per test class. The compose stack is only needed for
running the app by hand.

To run one scenario:

```bash
mvn test -Dtest=ResumableUploadIntegrationTest
```

---

## Observability check

With the staged strategy running (FR-058):

```bash
curl -s localhost:8080/actuator/metrics/copy.backlog.size
```

| Metric | Type | Requirement |
|---|---|---|
| `copy.backlog.size` | gauge | FR-011 |
| `copy.backlog.oldest.age` | gauge | Distinguishes a healthy steady backlog from a stalled one |
| `copy.delivery.completed` | counter | FR-058 |
| `copy.delivery.retries` | counter | FR-058 |
| `copy.delivery.failed.permanent` | counter | FR-019, FR-058 |
| `copy.release.outcome` | counter (tagged) | FR-067 |
| `copy.transfers.unfinished` / `.bytes` | gauge | FR-057 |
| `copy.checkpoint.errors` | counter | FR-058 |

Checkpoint-store availability appears as a health indicator at `/actuator/health`.

---

## Scenario-to-criterion coverage

| Scenario | Covers |
|---|---|
| S1 | SC-001, SC-003 |
| S2 | SC-002 |
| S3 | SC-004, SC-005 |
| S4 | SC-006 |
| S5 | SC-007, SC-008 |
| S6 | SC-009, SC-010 |
| S7 | SC-011, SC-012 |
| S8 | SC-013 |
| S9 | SC-017 |
| S10 | SC-016 |
| S11 | SC-018 |
| S12 | SC-019, SC-020 |
| S13 | SC-021, SC-022, SC-023 |
| S14 | FR-025, FR-039–FR-041 |
| S15 | SC-014, SC-015 |

All 23 success criteria are covered.
