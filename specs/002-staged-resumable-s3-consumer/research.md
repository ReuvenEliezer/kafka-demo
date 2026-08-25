# Phase 0 Research: Staged Consumer with Resumable Chunked Object Upload

**Feature**: `002-staged-resumable-s3-consumer` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

This document resolves every `NEEDS CLARIFICATION` raised in [plan.md](./plan.md)'s Technical Context, plus the
technology-choice and integration-pattern questions the spec deliberately left to planning. Each entry records the
decision, why it was chosen, and what was rejected.

The spec is unusually well-clarified (ten recorded clarification rounds), so most "what" questions are already settled.
What remains is "how", against this repository's concrete stack: Spring Boot 3.5.3, Java 21, Spring Kafka, AWS SDK v2,
Resilience4j, Testcontainers.

---

## R1. Staging store — PostgreSQL via Spring Data JPA

**Decision**: PostgreSQL 17, accessed through Spring Data JPA, schema managed by Flyway. Added to `docker-compose.yml`
for local/CI runs and driven by `PostgreSQLContainer` in tests.

**Rationale**: FR-007 through FR-019 demand, together, a store with: durable commit before acknowledgement; atomic
multi-row insert per consumed batch (FR-009); a per-item mutable delivery state (FR-010); an exclusive claim with an
expiry that a reaper can break (FR-016, FR-017); a cheap count of items in one state (FR-011); and ordered retry
scheduling (FR-018). That is a transactional relational workload, and `SELECT ... FOR UPDATE SKIP LOCKED` is the
canonical primitive for the claim semantics FR-016 describes — supported natively by PostgreSQL, contention-free across
workers, and requiring no lock table or advisory-lock bookkeeping of our own.

**Alternatives considered**:

- *Reuse Redis for staging too.* Rejected. The spec explicitly permits the checkpoint store to lose data (FR-032,
  FR-033) and explicitly forbids the staging store from losing it (FR-008, SC-006). Putting both in one store either
  over-hardens the disposable half or under-hardens the authoritative half. Keeping them separate is what makes
  "checkpoint loss costs a retransfer, nothing more" (SC-016) true by construction rather than by discipline.
- *An embedded store (H2, SQLite).* Rejected: `SKIP LOCKED` semantics and `JSONB` differ or are absent, and the user's
  standing testing preference is Testcontainers over H2 for anything touching persistence behaviour.
- *A Kafka compacted topic as the staging store.* Rejected: it reintroduces exactly the per-partition head-of-line
  blocking the spec's own Rejected-alternatives section identifies as a poor fit for transfers ranging from minutes to
  hours.

## R2. Checkpoint store — Redis hash per destination object

**Decision**: Redis 8, via `spring-boot-starter-data-redis` (Lettuce). One hash per destination object, keyed
`xfer:{bucket}:{objectKey}`, with a sliding TTL refreshed on every chunk confirmation. Confirmation writes go through a
single Lua script so the field write and the TTL refresh are one atomic round trip.

**Rationale**: The spec's clarification round already fixed Redis and the one-hash-per-destination shape (FR-027,
FR-030). What planning adds is the atomicity argument: `HSET` followed by a separate `EXPIRE` leaves a window in which a
crash between the two commands records a confirmed chunk under the *old*, shorter TTL — precisely the mid-flight expiry
FR-038 exists to prevent. A Lua script (`EVALSHA`) collapses both into one atomic server-side operation and one network
round trip, which also keeps per-chunk write cost negligible as the spec's assumptions require.

**Alternatives considered**:

- *`HSET` + `EXPIRE` pipelined.* Rejected: pipelining batches the round trips but does not make them atomic.
- *A Redis key per chunk with a shared prefix.* Rejected: the TTL would have to be refreshed across N keys per
  confirmation, and reading the resume state would need a `SCAN`. The hash gives one key to expire and one `HGETALL` to
  read.
- *Storing the checkpoint in PostgreSQL alongside the staged item.* Rejected on the spec's own terms — the clarification
  chose a fast expiring key-value store precisely so a write-per-chunk does not land on the transactional store, and so
  that losing this state is cheap rather than alarming.

## R3. Bounded memory during chunked upload — stream parts, disable SDK retry on `UploadPart`

**Decision**: Upload each part with `RequestBody.fromContentProvider(streamProvider, partSize, contentType)` reading
directly from the provider's HTTP response body, bounded to exactly `partSize` bytes. The `S3Client` used by the
delivery worker is built with retries disabled (`AwsRetryStrategy.doNotRetry()`); part-level failure is surfaced to our
own checkpoint-driven retry instead.

**Rationale**: This is the load-bearing decision behind SC-008 ("memory per in-flight transfer stays within a fixed
bound and does not scale with payload size"). It is worth being explicit about why the obvious approaches fail that
criterion. FR-026 derives chunk size *from* payload size, so part size itself grows with the payload — a 5 TiB object
needs ~537 MB parts. Any design that buffers a whole part in memory therefore scales with payload size and fails SC-008,
even though it "streams". The only way to hold memory flat is to pass the socket through: bytes move
provider-socket → bounded stream → S3-socket, with nothing larger than the HTTP client's own transfer buffer resident.

The catch is that the AWS SDK's built-in retry needs to `reset()` the request stream to re-send, and a stream pulled
live from another socket cannot be reset. Disabling SDK retries for this client is therefore not a workaround but the
correct wiring: a failed part must fall through to the checkpoint-driven resume path anyway (FR-031, FR-037), which
already knows how to restart the download at the right offset. An SDK-level retry would silently re-attempt against a
stream that has already been consumed.

**Alternatives considered**:

- *`S3TransferManager`.* Rejected, and this is the single most tempting wrong answer. It does multipart uploads with
  parallelism and its own retry, but its pause/resume token is an in-process object; it does not expose per-part ETags
  for external checkpointing, and it cannot resume across a process restart (FR-029, SC-003). It also parallelises
  chunks, which the spec puts out of scope (chunks must be sequential so confirmed chunks form a contiguous prefix,
  FR-042 — the property FR-043's derived resume position depends on).
- *Buffer each part to a temporary file, then upload the file.* Not rejected outright — it makes parts resettable and
  keeps heap flat — but rejected as the default because it converts a memory bound into a disk bound that still scales
  with part size, adds a full extra write+read of every byte, and needs its own cleanup path for crashed transfers.
  Recorded here as the fallback if a specific S3-compatible endpoint proves unable to accept a non-resettable stream.
- *Fixed small part size (e.g. always 5 MiB).* Rejected: 5 MiB × 10,000 parts caps the object at ~48 GiB, well under
  the 5 TiB the spec's environment assumptions require. This is exactly why FR-026 mandates deriving chunk size.

## R4. Chunk-size derivation

**Decision**:

```
partSize = clamp(
    max(configuredBasePartSize, ceilToMiB(payloadSize / MAX_PARTS)),
    MIN_PART_SIZE,
    MAX_PART_SIZE)
```

with `MAX_PARTS = 10_000`, `MIN_PART_SIZE = 5 MiB`, `MAX_PART_SIZE = 5 GiB`, and `configuredBasePartSize` defaulting to
16 MiB. Part count is `ceil(payloadSize / partSize)`; only the final part may fall below `MIN_PART_SIZE`.

**Rationale**: Satisfies FR-026 and the two edge cases it exists for. The `ceil(payloadSize / MAX_PARTS)` term is what
prevents the part-count ceiling being hit on very large payloads — at 5 TiB it yields ~537 MB parts, matching the figure
the spec's environment assumptions state. Rounding up to a MiB boundary keeps part sizes human-readable in logs and
keeps `confirmedChunks × partSize` exact in bytes, which FR-043's derived resume position depends on. The 16 MiB base
(rather than a base equal to the 100 MB threshold) means a payload just over the threshold still splits into several
parts, so resumability is real at the bottom of the chunked range rather than nominal.

The `MIN_PART_SIZE` floor interacts with the threshold: because the default threshold (100 MB) is far above 5 MiB, a
payload that qualifies for chunking always yields at least one full-size non-final part, so the "payload just over the
threshold produces an undersized non-final chunk" edge case cannot arise under default configuration. Under a lowered
test threshold it can, which is why the floor is applied rather than assumed away.

## R5. Startup validation of configuration ordering

**Decision**: A single `@ConfigurationProperties` root record (`CopyProperties`) whose **compact constructor** performs
all cross-field validation, throwing purpose-built exceptions (`InvalidChunkingThresholdException`,
`InvalidCheckpointExpiryException`). Binding failure fails the context, so the application does not start.

The invariants enforced (FR-025, FR-039, FR-040, FR-041):

```
threshold                                    <= 5 GiB          (S3 single-request maximum)
maxAttempts x maxBackoff  <  checkpointExpiry  <  abandonedUploadRetention
```

**Rationale**: Cross-field validation needs the whole property tree, so it cannot live in the sub-records; the root
record's compact constructor is the earliest point where the tree is complete. This also satisfies the standing rule
against `@PostConstruct` for initialisation logic — the object is either valid on construction or does not exist. Bean
Validation annotations (`@Min`, `@NotNull`) still cover the per-field cases; only the ordering relations need code.

**Alternatives considered**: an `ApplicationRunner` or `@EventListener(ApplicationReadyEvent.class)` check — rejected
because both let the application bind, wire, and in the runner's case start consuming before failing, which turns a
configuration error into a partial startup.

## R6. Strategy selection — property-driven profile activation

**Decision**: One user-facing knob, `copy.consumer.strategy: inline | staged`. A small `EnvironmentPostProcessor`
(`StrategyProfileActivator`, registered in `META-INF/spring.factories`) reads it and activates the matching profile
(`copy-inline` / `copy-staged`). Bean-level `@ConditionalOnProperty` on the same key guards each strategy's components;
the profile exists so that `application-copy-inline.yaml` can carry `spring.autoconfigure.exclude` for the JPA and Redis
auto-configurations.

**Rationale**: FR-004 sets a high bar — the inactive strategy must cause "no errors, warnings, or resource
consumption". `@ConditionalOnProperty` alone does not clear it: with `spring-boot-starter-data-jpa` on the classpath and
no `spring.datasource.url`, Boot's auto-configuration attempts an embedded database and fails the context outright. So
inline mode must *exclude* the auto-configurations, and `spring.autoconfigure.exclude` can only be varied by profile,
not by arbitrary property. The `EnvironmentPostProcessor` bridges the two so the user still sets one property, not two
knobs that can disagree.

**Alternatives considered**:

- *Expose the Spring profile directly as the knob.* Simpler, and still "configuration, not code" for FR-003. Rejected
  because `copy.consumer.strategy` is self-describing at the point of use and the profile name is not, and because
  bean-level conditionals reading a domain property document intent better than `@Profile`.
- *Two knobs (property + profile).* Rejected: two knobs that must agree is a configuration trap, and FR-004's "no
  errors" is exactly what a mismatch would violate.
- *Keep both listeners registered and set `autoStartup=false` on the inactive one.* Rejected: the inactive strategy's
  beans, connection pools, and (for staged) Redis and JPA infrastructure would still be created — resource consumption
  FR-004 forbids.

## R7. Where the existing circuit breaker points

**Decision**: The existing `s3Upload` circuit breaker and `KafkaBackpressureController` stay exactly as they are, but
both become conditional on the inline strategy. The staged strategy gets two new, independent breakers:
`s3Delivery` (guarding the delivery worker's S3 calls, FR-020) and `providerDownload` (guarding provider fetches,
FR-046). Neither is wired to consumer pause/resume.

**Rationale**: This resolves an apparent tension between FR-002 ("retain the existing strategy unchanged") and FR-020
("apply the circuit-breaking to the delivery worker rather than the consumption path"). Read together with the scope
boundary — "only the object-store failure-detection wiring is repointed" — the intent is clear: the inline strategy
keeps its breaker-driven pause because that is the behaviour it exists to demonstrate; the staged strategy must *not*
pause its consumer on S3 failure, because doing so would defeat SC-004 ("while the object store is completely
unavailable, the staged strategy continues consuming at its normal rate"). Under the staged strategy the only thing that
pauses consumption is backlog pressure (FR-012), never object-store health.

Two separate breakers rather than one shared instance is what FR-046 requires: an unhealthy provider must not trip the
object store's breaker or vice versa. They also need different tuning — provider throttling is expected and transient,
S3 unavailability is rarer and longer.

**Note on FR-002 and "unchanged"**: `KafkaConsumer` and `KafkaBackpressureController` each gain one
`@ConditionalOnProperty` annotation and nothing else. Their behaviour when the inline strategy is active is
byte-for-byte the behaviour they have today, which is what the comparison baseline requires.

## R8. Batched intake and the acknowledge-after-durable-staging ordering

**Decision**: A second listener container factory (`batchKafkaListenerContainerFactory`) with `setBatchListener(true)`,
`AckMode.MANUAL_IMMEDIATE`, `max.poll.records` bounding batch size and `fetch.max.wait.ms` bounding batch wait
(FR-006). The staged listener stages the whole batch inside one `@Transactional` method, and acknowledges **after** that
method returns — the acknowledgement is outside the transaction, never inside it.

**Rationale**: FR-008 and SC-006 hinge on the ordering, and the ordering is easy to get subtly wrong. Acknowledging
inside the transactional method would mean the offset commit can succeed while the enclosing transaction later rolls
back — losing the message. Acknowledging after commit means a crash in the gap causes redelivery, which is
at-least-once and is made harmless by deterministic naming (FR-052). The batch is one transaction, so FR-009's
all-or-nothing holds; a redelivered batch re-inserts rows that are deduplicated by a unique constraint on the
recording-file identifier (see [data-model.md](./data-model.md)).

`isolation.level=read_committed` is set on the staged consumer because the notification ingress publishes
transactionally (R9) — without it the consumer could read messages from a transaction that later aborts.

## R9. Notification ingress — all-or-nothing publish

**Decision**: A dedicated transactional `KafkaTemplate` (separate producer factory with a `transactional.id`,
`acks=all`, `enable.idempotence=true`), used via `executeInTransaction` so that the several messages of one notification
(FR-077) either all commit or none do (FR-078). The HTTP response is written only after the transaction commits
(FR-079).

**Rationale**: FR-078 asks for an atomic multi-message publish; Kafka transactions are the only mechanism that provides
it. Sending N messages and awaiting N futures gives durability per message but not atomicity — a failure on message 3 of
5 leaves 2 published and the notification un-acknowledged, so the provider retries and republishes those 2, which is a
partial publish recorded as a retryable failure. Deterministic naming makes the duplicate harmless, but FR-078 asks for
the stronger property, and transactions cost one extra round trip on a path that is otherwise trivial.

A *separate* producer factory is used so the existing non-transactional `MsgController` path is untouched (FR-002).

## R10. Signature verification

**Decision**: HMAC-SHA256 over `v0:{timestamp}:{rawBody}`, compared with `MessageDigest.isEqual` (constant-time,
FR-071). The raw body is captured before JSON parsing via a `ContentCachingRequestWrapper`. The signed timestamp is
range-checked against an injected `java.time.Clock` with a configurable freshness window (FR-072). Verification runs
before any parsing beyond extracting the timestamp (FR-070). The secret is bound from an environment variable and never
appears in `application.yaml` (FR-082).

**Rationale**: The two failure modes here are both classics. Verifying a *re-serialised* body rather than the raw bytes
breaks whenever the provider's JSON formatting differs from Jackson's by so much as a space — hence caching the raw
body. And `String.equals` on signatures leaks, through timing, how many leading bytes matched, which FR-071 calls out
explicitly. `MessageDigest.isEqual` is documented as constant-time and is the JDK's answer for this.

FR-072's phrasing is worth honouring precisely: including the timestamp in the signed material proves it was not
*tampered with*, but a captured-and-replayed notification carries a genuine signature over a genuine timestamp. Only a
range check against the current time stops the replay, which is why the timestamp is validated as a value and not merely
as signed input.

## R11. Provider download with range resume

**Decision**: `java.net.http.HttpClient` with `Range: bytes={resumePosition}-`. The response **status code** decides
what happens next: `206 Partial Content` means the provider honoured the range; `200 OK` means it did not, and the first
`resumePosition` bytes are read and discarded before uploading resumes (FR-045).

**Rationale**: Status code, not the `Content-Range` header, is the reliable discriminator — a provider that ignores
`Range` returns 200 with no `Content-Range` at all, and one that partially honours it still returns 206. This one branch
is the whole of SC-002: against an uncooperative provider the download leg is repeated but the upload leg is not, so the
expensive half of the copy is still saved.

`HttpClient` over the AWS SDK's HTTP client or `RestClient` because it exposes `BodyHandlers.ofInputStream()`, giving
the raw, unbuffered `InputStream` that R3's memory bound requires.

## R12. Credential minting and mid-transfer renewal

**Decision**: `ProviderClient` mints a fresh download credential at the start of every attempt (FR-060) from the stable
recording identifier held on the staged item (FR-059). During a transfer, the credential's remaining lifetime is checked
**at chunk boundaries**; if it falls below a configured margin, a new credential is minted and the download reconnected
with a `Range` header at the current derived resume position (FR-061).

**Rationale**: Chunk boundaries are the only safe renewal point, because they are the only points at which the derived
resume position (FR-043) is exactly correct — mid-chunk, some bytes have been read from the provider but not yet
confirmed by S3, and reconnecting there would need a byte offset the checkpoint deliberately does not store. Renewing at
a boundary reuses the ordinary resume path rather than adding a second one, which is why FR-061's "continuing from the
derived resume position rather than restarting" is cheap to satisfy.

## R13. Download-target allowlisting

**Decision**: Before any fetch, the resolved URL's host is checked against a configured allowlist of provider domains,
matching on exact host or a registrable-domain suffix; the scheme must be `https`. Redirects are followed only if each
hop also passes. Failure throws `DisallowedProviderHostException` and fails the item permanently.

**Rationale**: FR-062 asks for this because the download URL originates in a message whose content is, from the copy
worker's perspective, external input. Checking only the initial URL is the common incomplete implementation — an
allowlisted host that 302s to an internal address defeats the check, so `HttpClient` is configured with
`followRedirects(NEVER)` and redirects are handled explicitly with a per-hop check.

## R14. Integrity verification before the release signal

**Decision**: Three layers, all required before the release signal is permitted (FR-064):

1. Per-part integrity: `CRC32C` checksums sent with each `UploadPart` via the SDK's trailing-checksum support, so S3
   rejects a corrupted part on receipt rather than at completion.
2. Full-object integrity: `CreateMultipartUpload` is issued with `ChecksumAlgorithm.CRC32_C` and
   `ChecksumType.FULL_OBJECT`, so `CompleteMultipartUpload` returns a checksum over the assembled object, compared
   against the CRC32C accumulated over the bytes as they streamed through.
3. Size: `HeadObject` after completion, `contentLength` must equal the expected payload size.

Only after all three pass does the item reach `DELIVERED`, and only a `DELIVERED` item may be released (FR-065).

**Rationale**: The spec calls a premature release "the one unrecoverable failure in the feature", so verification is
gated on evidence from the object store rather than on our own belief that we finished. Layer 3 alone would catch
truncation but not corruption; layer 1 alone validates parts in isolation but not their assembly or ordering; layer 2 is
what actually proves the finalized object matches what we read.

**Dependency implication**: full-object checksums on multipart uploads require a newer AWS SDK than the pinned 2.28.11.
The SDK is bumped to **2.35.10** (already present in the local Maven repository, so the build stays offline-capable).
If that bump proves undesirable, the degraded fallback is layers 1 and 3 plus a CRC32C recorded as object metadata for
out-of-band audit — weaker, and flagged as such rather than silently substituted.

## R15. Deterministic destination naming

**Decision**: `recordings/{providerAccountId}/{sessionId}/{recordingFileId}` — derived from the individual recording
*file* identifier, not the notification or session (FR-052). Character-sanitised and length-bounded; the raw identifier
is retained on the staged item so the mapping is auditable.

**Rationale**: FR-052 is explicit that session-level naming is a collision bug, because one notification describes
several files of the same session (FR-077). Including the file identifier makes redelivery an overwrite of the same key
(harmless, at-least-once as the spec targets) while keeping sibling files distinct. S3 `PutObject` and
`CompleteMultipartUpload` are both atomic replacements, so no partially written object is ever readable (FR-053) without
extra work.

## R16. Reaping abandoned multipart uploads

**Decision**: A `@Scheduled` reaper listing in-progress multipart uploads (`ListMultipartUploads`) and aborting those
older than the configured retention window, which R5 validates to exceed the maximum retry span (FR-055, FR-056). It
reports count and aggregate size, including uploads whose checkpoint has already expired and which are therefore no
longer resumable (FR-057).

**Rationale**: S3 does not expire unfinished multipart uploads on its own and bills for their storage, which is why
FR-055 exists at all. The retention-window ordering enforced at startup is what keeps FR-056 true — the reaper can never
abort an upload that a scheduled retry is still entitled to resume, because the window is strictly longer than the
longest possible retry span.

An S3 lifecycle rule (`AbortIncompleteMultipartUpload`) is a genuine alternative and cheaper to operate, but its
granularity is whole days and it reports nothing, so FR-057's reporting requirement would need the listing pass anyway.

## R17. Backlog governance

**Decision**: A `@Scheduled` `BacklogGovernor` reads the count of undelivered staged items and calls
`container.pause()` above the high-water mark and `container.resume()` below the low-water mark (FR-012), logging a
distinctly identifiable status change with reason and backlog size on each transition (FR-014).

**Rationale**: Spring Kafka's `pause()` suspends record *delivery* while the container keeps calling `poll()`, so the
consumer stays in its group and no rebalance occurs — exactly what FR-013 and SC-013 require, and the same mechanism the
existing `KafkaBackpressureController` already relies on. Distinct high and low marks give hysteresis; a single
threshold would flap.

Because pause takes effect between polls and never mid-batch, the "capacity reached while a batch is in flight" edge
case resolves itself: the in-flight batch is staged and acknowledged in full, and the pause applies to the next poll.

## R18. Claim ownership across long transfers

**Decision**: Claiming is a short transaction (`SELECT ... FOR UPDATE SKIP LOCKED` → `UPDATE` state, `claim_owner`,
`claim_expires_at`), immediately committed. The transfer then runs **outside** any transaction, extending
`claim_expires_at` on every chunk confirmation as a progress heartbeat. A reaper releases claims whose expiry has
passed (FR-017).

**Rationale**: A transfer can take hours; holding a row lock or an open transaction for that long would pin a database
connection, block vacuum, and turn a worker crash into a lock that only a connection timeout can clear. Separating the
short claim transaction from the long unit of work is what makes FR-017's "released a claim whose holder has stopped
making progress" implementable — the heartbeat is the definition of progress, and it rides along on work the transfer
is doing anyway (FR-038's TTL refresh happens at the same point).

## R19. Observability

**Decision**: Micrometer via `spring-boot-starter-actuator`. Gauges for backlog size and undelivered-item age; counters
for deliveries, retries, permanent failures, release-signal outcomes, and checkpoint-store errors; a timer for delivery
duration; a health indicator for checkpoint-store availability (FR-011, FR-057, FR-058, FR-067).

**Rationale**: FR-058 enumerates the required signals and Micrometer is already transitively present through Boot. The
one non-obvious gauge is *undelivered-item age* rather than count alone: a backlog of constant size can be either
healthy throughput or a stalled queue, and only age distinguishes them.

## R20. Test doubles for the provider

**Decision**: A purpose-built `FakeProviderServer` test fixture on `com.sun.net.httpserver.HttpServer`, serving
deterministic synthetic bytes with genuine `Range` support and switchable faults: ignore-range (serve 200 from byte 0),
fail-after-N-bytes, expire-credential-after-N-bytes, throttle, and delete-recording.

**Rationale**: The provider behaviours the tests must exercise are precisely the ones general-purpose HTTP stubs handle
worst. WireMock has no real `Range` support and cannot stream a synthetic multi-megabyte body without materialising it,
which would defeat the memory-bound test (SC-008). A ~150-line fixture generating bytes from a seeded pattern serves
arbitrary sizes at zero memory cost and makes byte-identity assertions (SC-007) exact.

**Alternatives considered**: LocalStack S3 as a stand-in for the provider — rejected, because it cannot be made to
*ignore* a `Range` header, and SC-002 exists specifically to test that path.

## R21. Java/Spring conventions for new code

**Decision**: New code follows the project's standing Java/Spring conventions: `record` for DTOs and value objects,
constructor injection only (`@RequiredArgsConstructor`), `@Slf4j` for logging, `java.time.Duration` in both config and
signatures, injected `java.time.Clock` wherever time enters business logic, domain-specific exceptions over JDK generics,
strict controller/service/repository layering, and `@RestControllerAdvice` for error mapping. Strategy selection between
the single-request and chunked upload paths uses the enum-keyed auto-registration pattern rather than a branch.

Lombok is added to `pom.xml` (provided scope) for the new code. **Existing files keep their current style** — the spec's
scope boundary preserves the inline strategy as-is, and rewriting its logging idiom would be an unrelated change.

**Trade-off, stated plainly**: this leaves the repository with two logging idioms — `LogManager.getLogger` in the five
pre-existing classes, `@Slf4j` in roughly thirty new ones. For a repository whose purpose is to be read as a reference,
that inconsistency is a real cost. It is accepted because the standing convention is explicit and because the
alternative — modifying the baseline strategy — is what the spec forbids. Converting the older files is a
one-commit follow-up if the mixed state proves more annoying than the churn.

**Version policy**: Java 21 and Spring Boot 3.5.3 are retained. CI pins JDK 21, and moving to Java 25 / Boot 4.1 would
be a repository-wide change unrelated to this feature. The only dependency bump this feature requires on its own merits
is the AWS SDK (R14).

---

## Resolved unknowns summary

| Unknown from Technical Context | Resolved by | Resolution |
|---|---|---|
| Durable staging store technology | R1 | PostgreSQL + Spring Data JPA + Flyway |
| Checkpoint store technology and access pattern | R2 | Redis hash per destination, Lua-atomic confirm+TTL |
| How memory stays bounded when part size scales | R3, R4 | Pass-through streaming, SDK retry disabled |
| Chunk-size formula | R4 | Derived from payload size, clamped to S3 limits |
| Where startup validation lives | R5 | Root `@ConfigurationProperties` compact constructor |
| How a strategy is selected without code change | R6 | Property → profile activation |
| Where the circuit breaker points | R7 | Inline keeps `s3Upload`; staged adds `s3Delivery`, `providerDownload` |
| Batch intake and ack ordering | R8 | Batch listener, ack after transactional commit |
| All-or-nothing multi-message publish | R9 | Dedicated transactional `KafkaTemplate` |
| Signature scheme and replay defence | R10 | HMAC-SHA256 over raw body, constant-time, clock-checked |
| Range resume and uncooperative providers | R11 | 206-vs-200 branch, discard prefix |
| Credential renewal point | R12 | Chunk boundaries only |
| SSRF defence on download targets | R13 | Per-hop host allowlist, redirects not auto-followed |
| Integrity evidence before release | R14 | Part checksums + full-object CRC32C + size; SDK bump |
| Destination naming | R15 | Keyed by recording *file* identifier |
| Abandoned upload reclamation | R16 | Scheduled `ListMultipartUploads` + abort, with reporting |
| Backlog pause/resume without rebalance | R17 | `container.pause()` with hysteresis |
| Claim held across a multi-hour transfer | R18 | Short claim txn + heartbeat-extended expiry |
| Required metrics | R19 | Micrometer counters/gauges/timer + health indicator |
| Provider test double | R20 | Purpose-built streaming `FakeProviderServer` |
| Code conventions and versions | R21 | Standing conventions for new code; Java 21 / Boot 3.5.3 retained |

**No `NEEDS CLARIFICATION` items remain.**
