# Feature Specification: Parallel Ranged Transfer

**Feature Branch**: `003-parallel-ranged-transfer`

**Created**: 2026-08-25

**Status**: Draft

**Input**: User description: "specs/003-parallel-ranged-transfer/design-notes.md — cut wall-clock transfer time for large payloads by fetching and uploading multipart chunks in parallel instead of strictly sequentially, replacing the contiguous-prefix checkpoint with sparse-set semantics, adding a bounded byte range to the provider contract, and falling back to sequential transfer where parallelism is impossible."

## Overview

Feature 002 delivers a copy pipeline that **survives** interruption: a very large recording is fetched from a third-party provider and written to the company's object store as a sequence of chunks, each confirmed as it lands, so a failure at 90% resumes at 90% rather than at zero. That feature deliberately transfers chunks **strictly in order** — download chunk N, upload chunk N, confirm it, then start N+1 — and just as deliberately declared chunk-level parallelism out of scope, because resumability, not throughput, was its goal.

The consequence is that the wall-clock time of a copy is the **sum** of every chunk's transfer time, and the whole copy is capped at the throughput of a single connection to the provider and a single connection to the object store. For a multi-hundred-gigabyte recording that is hours of elapsed time during which the source recording cannot be released, a delivery worker is occupied, and an operator watching the backlog sees no movement.

This feature makes those chunks move **concurrently**. Several chunks of the *same* recording are fetched and stored at the same time, so a copy finishes in roughly the time of its slowest concurrent group rather than the sum of all its chunks.

Three things stand in the way, and this feature is defined by resolving them rather than by the concurrency itself:

1. **The resumption model assumes order.** Confirmed chunks are tracked as a contiguous prefix and the resume position is derived from its length. Concurrent completion creates gaps by construction — chunk 5 finishing before chunk 3 — and a derived resume position then points at the wrong bytes. The resumption model must become a record of *which chunks are still missing*, and the derived resume position must be removed rather than redefined.
2. **The provider request is open-ended.** The copy asks the provider for "everything from position N onward", which is exactly right for resuming and exactly wrong for parallelism: several concurrent open-ended requests would each stream to the end of the recording, multiplying the bandwidth cost by the degree of concurrency. Requests must carry an **upper** bound as well as a lower one.
3. **Parallelism must not be bought with memory.** Feature 002's hard constraint — bytes move from the provider's connection through a bounded stream to the object store's connection, with no chunk ever held whole — still holds. Concurrency multiplies the number of connections in flight, not the memory held per connection.

Because the entire premise is a throughput win that may not exist, this feature begins with a measurement that is allowed to **cancel it**. If the provider's outbound bandwidth is already the bottleneck, concurrent chunks will contend for the same constrained pipe and buy nothing, and the remaining work is waste.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Prove the speed-up is available before building it (Priority: P1)

Before any change to how chunks move, an engineer needs to know where a copy's time actually goes. They run a large transfer under instrumentation and get, per chunk, how long was spent reading from the provider versus writing to the object store, and the rate achieved on each leg. From that they can tell whether the provider's outbound bandwidth is already saturated by a single connection — in which case concurrency has nothing to win — or whether each leg is idle much of the time waiting on the other, in which case it has a great deal to win.

**Why this priority**: This is the cheapest work in the feature and the only work that can make the rest unnecessary. Building concurrency first and measuring afterwards risks delivering a large, invasive change to the resumption model that yields no improvement.

**Independent Test**: Run a multi-chunk transfer against a provider whose bandwidth can be deliberately constrained, and confirm the recorded per-chunk timings correctly attribute time to the read leg and the write leg — verified by constraining one leg at a time and seeing that leg's attributed time grow while the other's does not.

**Acceptance Scenarios**:

1. **Given** a transfer of a payload spanning many chunks, **When** it completes, **Then** a per-chunk record is available showing bytes moved, elapsed time on the read leg, elapsed time on the write leg, and the rate achieved on each.
2. **Given** a provider artificially constrained to a low bandwidth, **When** a transfer runs, **Then** the read leg accounts for the overwhelming majority of elapsed time and the measurement identifies the provider as the bottleneck.
3. **Given** an object store artificially constrained to a low bandwidth, **When** a transfer runs, **Then** the write leg accounts for the overwhelming majority of elapsed time.
4. **Given** neither leg is constrained, **When** a transfer runs, **Then** the sum of the two legs' attributed time accounts for essentially all of the transfer's wall-clock time, so no significant time is unattributed.

---

### User Story 2 - A very large recording copies in a fraction of the elapsed time (Priority: P1)

An operator watching a very large recording being copied sees it finish substantially sooner than it does today, because several of its chunks are moving at once instead of one at a time. Nothing else about the outcome changes: the stored object is byte-identical to the source, and the source is released only after the copy is finalized and verified.

**Why this priority**: This is the feature's entire purpose. It is what shortens the window during which a worker is occupied and the source recording cannot be released.

**Independent Test**: Copy the same large payload twice — once with concurrency disabled and once enabled — against a provider that is *not* bandwidth-limited, and compare elapsed time and the byte-for-byte equality of the two stored objects.

**Acceptance Scenarios**:

1. **Given** a payload spanning many chunks and a provider able to serve several concurrent readers at full rate, **When** it is copied with concurrency enabled, **Then** the elapsed time is materially shorter than the same copy performed one chunk at a time, and the stored object is byte-identical to the source.
2. **Given** the same payload, **When** the configured degree of concurrency is raised, **Then** elapsed time decreases or holds steady, and never increases in a way that suggests the parallel path is slower than the sequential one.
3. **Given** a payload small enough to be a single chunk, **When** it is copied, **Then** it takes the same path and the same time as before, with no concurrency machinery engaged.
4. **Given** concurrency is configured off, **When** a payload is copied, **Then** it behaves exactly as it did before this feature, chunk by chunk in order.

---

### User Story 3 - An interrupted parallel transfer resumes without re-moving confirmed chunks (Priority: P1)

A copy is interrupted while several of its chunks are in flight. Some chunks beyond the interruption point had already been confirmed, and some before it had not — the confirmed set has holes in it. On retry, the copy moves exactly the chunks that are missing, in whatever order, and leaves the confirmed ones alone. The finalized object is still correct.

**Why this priority**: Concurrency creates holes in the confirmed set by construction. Without this, feature 002's central promise — an interruption costs the missing part, not the whole transfer — is broken the moment concurrency is switched on, and worse, a resumption model that assumes contiguity would resume from a wrong position and produce a corrupt object.

**Independent Test**: Force a transfer to confirm a deliberately non-contiguous set of chunks, then interrupt and retry it, and verify from the transfer's own records that only the missing chunks moved across both legs and that the stored object is byte-identical to the source.

**Acceptance Scenarios**:

1. **Given** a transfer whose confirmed chunks are 1, 2, 4, 5 and 7 of 8, **When** it is interrupted and retried, **Then** exactly chunks 3, 6 and 8 are fetched and stored, and no confirmed chunk is fetched or stored again.
2. **Given** the same interrupted transfer, **When** it completes on retry, **Then** the finalized object is byte-identical to the source.
3. **Given** a transfer whose resumption record has been lost entirely, **When** it is retried, **Then** it restarts from the beginning and completes correctly — absence of the record still means "restart", never "already done".
4. **Given** a transfer that reaches finalization, **When** any chunk is still unconfirmed, **Then** finalization is refused rather than producing a partial object.
5. **Given** a transfer interrupted and retried repeatedly at random points, **When** it eventually completes, **Then** the stored object is byte-identical to the source every time.

---

### User Story 4 - A provider that will not serve byte ranges still works (Priority: P2)

Some providers ignore a request for a specific slice of a recording and serve the whole thing from the beginning. Copies from such a provider still succeed — they simply do not go faster. The system detects the situation and transfers that payload one chunk at a time rather than opening several connections that would each have to read and discard most of the recording.

**Why this priority**: Concurrency depends entirely on the provider honouring byte ranges, and that is not guaranteed at runtime. Without an automatic fall-back, enabling this feature would turn a slow-but-working copy into a catastrophically wasteful one — every concurrent reader downloading and discarding a large prefix.

**Independent Test**: Point the copy at a provider configured to ignore range requests and serve from position zero, and confirm the copy completes correctly, that its total bytes read from the provider do not multiply with the configured degree of concurrency, and that the fall-back is visible to an operator.

**Acceptance Scenarios**:

1. **Given** a provider that ignores the requested byte range, **When** a multi-chunk payload is copied, **Then** the copy completes correctly and the stored object is byte-identical to the source.
2. **Given** the same provider, **When** the copy runs with concurrency configured on, **Then** the total bytes read from the provider are not multiplied by the degree of concurrency.
3. **Given** the same provider, **When** the copy falls back, **Then** the fall-back and its reason are recorded where an operator can see them.
4. **Given** a provider that honours ranges, **When** a copy runs, **Then** the total bytes read from the provider across all its connections is approximately the payload size — concurrency does not cause the same bytes to be read more than once.

---

### User Story 5 - A long concurrent transfer keeps its claim and its credentials (Priority: P2)

A copy large enough to run for hours, with several chunks always in flight, is not interrupted by housekeeping: no other worker concludes it is abandoned and takes it over, and the time-limited credential used to read from the provider is renewed before it expires without discarding the progress of the chunks already moving.

**Why this priority**: Both of these mechanisms were built for a single stream of work advancing one chunk at a time. Under concurrency they can misfire in ways that silently undo the feature's benefit — a live transfer reclaimed as abandoned, or every in-flight chunk restarted at once on credential renewal.

**Independent Test**: Run a transfer configured to outlive both its claim window and its credential lifetime several times over, with several chunks always in flight, and confirm it completes once, is never taken over by a second worker, and never restarts a chunk that was already confirmed.

**Acceptance Scenarios**:

1. **Given** a transfer whose duration exceeds its claim window several times over, **When** its slowest chunk is still in flight and no chunk has completed recently, **Then** the claim is still kept alive and no other worker takes the item over.
2. **Given** a transfer whose duration exceeds its credential's lifetime, **When** the credential approaches expiry, **Then** it is renewed and the transfer completes without restarting from the beginning.
3. **Given** the same transfer, **When** the credential is renewed, **Then** no already-confirmed chunk is transferred again.
4. **Given** a transfer that genuinely dies with chunks in flight, **When** its claim window elapses, **Then** it is reclaimed exactly as before this feature, and its retry resumes from the confirmed chunks.

---

### User Story 6 - Concurrency does not cost memory (Priority: P2)

An operator raising the degree of concurrency sees the copy get faster without seeing memory grow with the size of the recordings being copied. The memory cost of a copy is a function of how many chunks are in flight, not of how large those chunks are.

**Why this priority**: The obvious implementation of "move several chunks at once" is to hold several chunks in memory, which would defeat feature 002's streaming guarantee and make large payloads fail outright. Stating it as an observable outcome makes it testable rather than aspirational.

**Independent Test**: Measure peak memory attributable to one in-flight copy across payloads differing by orders of magnitude, at a fixed degree of concurrency, and confirm the peak is flat with respect to payload size.

**Acceptance Scenarios**:

1. **Given** a fixed degree of concurrency, **When** copies of payloads differing by at least three orders of magnitude are run, **Then** peak memory attributable to one in-flight copy does not grow with payload size.
2. **Given** a fixed payload, **When** the degree of concurrency is raised, **Then** peak memory grows at most in proportion to the degree of concurrency, and no single chunk is ever held whole.
3. **Given** the maximum permitted chunk count for a payload, **When** it is copied, **Then** the number of simultaneously open provider connections stays within the configured bound rather than scaling with the chunk count.

---

### Edge Cases

- What happens when a chunk fails permanently while its siblings are mid-flight? The confirmed siblings must remain confirmed and reusable on retry; the attempt must end without finalizing a partial object.
- What happens when the resumption record is lost while several chunks are in flight? The attempt must be abandoned rather than finalized, and the retry must restart the transfer — never treat the absent record as completion.
- What happens when the last chunk, which is shorter than the rest, is fetched concurrently with full-size chunks? Its bounded request must stop at the true end of the payload, and a provider that returns fewer bytes than requested for a full-size chunk must be treated as a failure, not as a short chunk.
- What happens when the declared payload size is wrong and the payload is longer or shorter than expected? Bounded requests are derived from that size, so a mismatch must surface as an integrity failure rather than as a silently truncated object.
- What happens when the provider throttles or refuses connections once several are opened at once? The copy must degrade — fewer concurrent chunks, or sequential — rather than failing the whole item.
- What happens when the same item is retried while a previous attempt's connections are still draining? Confirmed chunks must not be double-counted and the object must not be finalized twice.
- What happens when a payload has only two or three chunks? Concurrency must not cost more in set-up than it saves.
- What happens when the object store rejects a chunk because a concurrent attempt already stored a different version of it? The retry must converge on one correct object rather than mixing chunks from two attempts.
- What happens when concurrency is enabled but every worker is already busy? Per-copy concurrency must not starve other items in the backlog of the capacity to make progress.

## Requirements *(mandatory)*

### Functional Requirements

#### Measurement gate

- **FR-001**: The system MUST record, for each chunk of a transfer, the bytes moved, the elapsed time attributable to reading from the provider, the elapsed time attributable to writing to the object store, and the rate achieved on each leg.
- **FR-002**: The measurement in FR-001 MUST be obtainable without enabling concurrent transfer, so it can be gathered from the existing sequential path.
- **FR-003**: The measurement MUST attribute essentially all of a transfer's wall-clock time to one leg or the other, so that a bottleneck can be identified rather than inferred.
- **FR-004**: The measurement MUST remain available after concurrency is introduced, so the achieved improvement can be confirmed against the predicted one rather than assumed.
- **FR-005**: The decision to build concurrent transfer MUST be gated on this measurement showing that a single sequential transfer does not already saturate the provider's outbound bandwidth. If it does, the remaining requirements in this specification MUST NOT be implemented.

#### Bounded byte-range requests

- **FR-006**: The system MUST be able to request a payload slice from the provider with both a start and an end position, rather than only a start position.
- **FR-007**: The end position for a chunk MUST be derived from that chunk's position and size, and the final chunk's end position MUST be the true end of the payload rather than a full chunk beyond its start.
- **FR-008**: The system MUST continue to support an open-ended request from a start position, since the sequential fall-back path still uses it.
- **FR-009**: The system MUST detect when the provider serves a different slice than the one requested — including serving the whole payload from the beginning — and MUST NOT treat the returned bytes as if they were the requested slice.
- **FR-010**: The system MUST treat a slice that ends before the requested end position as a transfer failure for that chunk, except for the final chunk of the payload.
- **FR-011**: The test provider used to exercise the system MUST honour bounded ranges, so that the concurrent path is exercised rather than only the fall-back.

#### Resumption without ordering

- **FR-012**: The system MUST record which specific chunks of a transfer have been confirmed, and MUST derive what remains to be transferred as the set of chunk positions that are absent from that record.
- **FR-013**: The system MUST NOT require the confirmed chunks to form a contiguous run, and MUST NOT compute progress as the length of a leading run of confirmed chunks. This supersedes feature 002's FR-042 and FR-043.
- **FR-014**: The system MUST NOT derive or store a single "resume position" for the transfer as a whole; each outstanding chunk MUST determine its own read position from its own chunk position.
- **FR-015**: The system MUST continue to treat a missing or expired resumption record as an instruction to restart the transfer from the beginning, and MUST NEVER interpret it as evidence that the transfer completed. Feature 002's FR-032 and FR-033 remain in force unchanged.
- **FR-016**: The system MUST refuse to finalize a transfer while any chunk position lacks a confirmation, and MUST report which positions are missing.
- **FR-017**: The system MUST keep the resumption record's expiry sliding on progress, and MUST refresh it whenever any chunk is confirmed regardless of that chunk's position.
- **FR-018**: The resumption record MUST remain a single record per destination object, so that concurrent chunk confirmations for one transfer do not fragment it.
- **FR-019**: Concurrent confirmations of different chunks of the same transfer MUST NOT overwrite one another, and MUST NOT be able to lose a confirmation that was already recorded.

#### Concurrent transfer

- **FR-020**: The system MUST be able to fetch and store several chunks of the same payload at the same time.
- **FR-021**: Each concurrently transferred chunk MUST read from its own bounded request covering exactly that chunk, and MUST NOT share a single stream with another chunk.
- **FR-022**: The degree of concurrency within a single transfer MUST be bounded by configuration, and MUST NOT scale with the payload's chunk count. A payload at the maximum permitted chunk count MUST NOT open a number of connections proportional to that count.
- **FR-023**: The degree of concurrency MUST be configurable down to one, and a value of one MUST produce behaviour equivalent to the sequential path, so the feature can be switched off without removing it.
- **FR-024**: The system MUST NOT hold a whole chunk in memory in order to transfer it concurrently — bytes MUST continue to move from the provider connection through a bounded stream to the object store connection. Feature 002's streaming constraint applies unchanged to every concurrent chunk.
- **FR-025**: The system MUST bound the total number of simultaneously open provider connections across all in-flight transfers [NEEDS CLARIFICATION: is the concurrency budget per transfer only, so total load is worker-count × per-transfer concurrency, or is there also a service-wide ceiling shared across transfers?].
- **FR-026**: A payload small enough to require no chunking MUST be unaffected by this feature, taking the same path it does today.
- **FR-027**: The system MUST confirm each chunk as it completes, rather than accumulating confirmations until a group of concurrent chunks all finish.

#### Failure handling under concurrency

- **FR-028**: When one chunk of a transfer fails, the system MUST preserve the confirmations of chunks that already succeeded, and MUST end the attempt without finalizing.
- **FR-029**: When one chunk of a transfer fails, the system MUST NOT leave other chunks of that transfer running indefinitely, and MUST release their provider and object-store connections.
- **FR-030**: The system MUST continue to classify provider-side failures separately from object-store failures under concurrency, so that a provider that throttles concurrent readers does not trip the object store's failure detection. Feature 002's FR-046 remains in force.
- **FR-031**: When the provider refuses or throttles concurrent connections, the system MUST reduce the number of concurrent chunks for that transfer rather than failing the item outright.
- **FR-032**: The system MUST fall back to transferring chunks one at a time, in order, when the provider does not honour bounded byte ranges, and MUST NOT open concurrent connections that would each discard a large prefix of the payload.
- **FR-033**: The fall-back in FR-032 MUST be detected per transfer at the time of transfer, not assumed from static configuration, and MUST be recorded where an operator can observe how often it occurs.
- **FR-034**: The system MUST keep the claim on an item alive for as long as any of that item's chunks is still in flight, and MUST NOT let the claim lapse merely because no chunk has been confirmed recently.
- **FR-035**: The system MUST renew a time-limited provider credential before it expires while chunks are in flight, and MUST NOT discard the progress of chunks that were already confirmed when it does.
- **FR-036**: The system MUST NOT require every in-flight chunk to be restarted in order to renew a credential; at most the chunks that are still unconfirmed may be re-read.
- **FR-037**: The system MUST leave the reclamation of abandoned partial uploads working unchanged, so a transfer that dies with chunks in flight still has its partial state reclaimed within the configured window.

#### Observability

- **FR-038**: The system MUST report, per transfer, the degree of concurrency actually used and whether the sequential fall-back was taken.
- **FR-039**: The system MUST report the count of chunks outstanding for an in-flight transfer, so that a stalled transfer is distinguishable from a slow one.
- **FR-040**: The system MUST report the count of transfers that fell back to sequential because the provider ignored bounded ranges, so a provider-side regression is visible.

### Key Entities

- **Chunk Assignment**: One unit of concurrent work — a chunk position paired with the exact byte span of the payload it covers. Self-contained: it carries everything needed to fetch and store that chunk without reference to any other chunk's progress. Replaces the implicit "wherever the shared stream has reached" of the sequential path.
- **Outstanding Chunk Set**: The set of chunk positions with no confirmation on record — the transfer's remaining work. Derived on demand from the resumption record and the total chunk count; never stored. Replaces the derived resume position, which becomes meaningless once chunks may complete out of order.
- **Transfer Checkpoint**: Unchanged in role from feature 002 — the resumption state for one in-progress chunked upload, one record per destination object, disposable by design, absence meaning "restart" and never "done". Changed in interpretation: its confirmed chunks are now a **set that may have holes**, not a contiguous prefix, and it no longer yields a whole-transfer resume position.
- **Transfer Leg Measurement**: Per chunk, the bytes moved and the time attributable to reading from the provider versus writing to the object store. The evidence on which the decision to build — or abandon — concurrency rests, and afterwards the evidence that it worked.
- **Range Capability**: Whether a given provider actually served the byte span that was requested of it, determined per transfer at transfer time rather than configured. Decides between the concurrent and sequential paths.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a transfer of a payload spanning many chunks, the recorded per-chunk timings account for at least 95% of the transfer's wall-clock time, split between the read leg and the write leg.
- **SC-002**: Constraining one leg's bandwidth to a small fraction of the other's causes that leg's attributed share of time to exceed 90%, confirming the measurement correctly identifies a bottleneck.
- **SC-003**: Against a provider able to serve concurrent readers at full rate, a payload spanning at least 16 chunks completes at least 3x faster in wall-clock time with the default degree of concurrency than with concurrency set to one. [NEEDS CLARIFICATION: is 3x the right bar for proceeding past the measurement gate, or should a different minimum improvement justify the change to the resumption model?]
- **SC-004**: Raising the degree of concurrency never increases wall-clock time for the same payload against an unconstrained provider.
- **SC-005**: Every finalized object is byte-identical to its source payload, verified across payloads below the chunking threshold, just above it, and spanning many chunks, with concurrency both enabled and disabled.
- **SC-006**: A transfer interrupted with a deliberately non-contiguous set of confirmed chunks moves, on retry, only the missing chunks across **both** legs — the bytes read from the provider and the bytes written to the object store each approximate the missing fraction of the payload, not the whole of it.
- **SC-007**: Across at least 50 transfers each interrupted at a randomly chosen moment with chunks in flight, every one eventually completes with a byte-identical object and zero partial objects finalized.
- **SC-008**: Losing the resumption record mid-transfer costs re-transfer and nothing else — no object is finalized incomplete and every affected transfer still completes correctly.
- **SC-009**: Against a provider that honours bounded ranges, the total bytes read from the provider for one transfer is within 5% of the payload size regardless of the degree of concurrency — concurrency never re-reads the same bytes.
- **SC-010**: Against a provider that ignores byte ranges, every transfer still completes with a byte-identical object, and total bytes read from the provider do not grow with the configured degree of concurrency.
- **SC-011**: Peak memory attributable to one in-flight transfer does not grow with payload size — measured across payloads differing by at least three orders of magnitude at a fixed degree of concurrency, with the largest payload's peak within 2x of the smallest's.
- **SC-012**: At a fixed payload size, peak memory attributable to one in-flight transfer grows no faster than proportionally with the degree of concurrency.
- **SC-013**: A payload at the maximum permitted chunk count is copied with the number of simultaneously open provider connections never exceeding the configured bound.
- **SC-014**: A transfer whose duration exceeds its claim window several times over, with chunks always in flight, is never taken over by a second worker and results in exactly one stored object.
- **SC-015**: A transfer whose duration exceeds its provider credential's lifetime completes without re-transferring any chunk that was already confirmed.
- **SC-016**: A transfer that dies with chunks in flight has its partial state fully reclaimed within the configured retention window, leaving no unbounded growth in storage cost.
- **SC-017**: When one chunk fails permanently, the attempt ends within the configured chunk timeout rather than waiting for its concurrent siblings to finish, and no partial object is finalized.
- **SC-018**: Setting the degree of concurrency to one reproduces the pre-feature behaviour exactly — same order of chunk transfer, same number of provider connections, same stored object.
- **SC-019**: A provider that begins refusing connections once several are open causes the affected transfer to complete at reduced concurrency rather than failing the item.
- **SC-020**: An operator can determine, for any completed transfer, the degree of concurrency it achieved and whether it fell back to sequential, without reading application logs line by line.

## Assumptions

### Decisions made where the description left a choice

- **The measurement phase is in scope as deliverable work, not as a throwaway experiment.** The design notes call it "phase 0" and note it can cancel the project. Treating it as a permanent capability (FR-001 to FR-004) rather than a temporary probe costs little and pays twice: once to decide whether to proceed, and again afterwards to confirm the achieved improvement matches the predicted one. It is also the only part of this feature that delivers value if the rest is cancelled.
- **The sequential path is retained permanently, not removed.** The design notes call for falling back to it when the provider ignores ranges. That makes it a live code path with its own correctness obligations, not legacy code — which is also what makes a concurrency setting of one meaningful as an off switch (FR-023, SC-018).
- **Range capability is decided per transfer, at transfer time.** A provider's behaviour can change between deployments and can differ per recording. Deciding from static configuration would risk the exact catastrophic case the fall-back exists to prevent — several concurrent readers each discarding a large prefix.
- **The whole-transfer resume position is deleted rather than redefined.** Once chunks may complete out of order there is no single position from which the transfer as a whole resumes, and any value computed for one would be a plausible-looking wrong answer. Each outstanding chunk derives its own read position from its own position instead.
- **Provider throttling degrades concurrency rather than failing the item.** A provider that tolerates one reader but not eight is a configuration problem, not a permanently failed recording, and failing the item would make enabling this feature strictly worse than not having it.

### Rejected alternatives

- **Adopting a managed transfer library or its high-performance client.** Rejected before specification. Such a library's parallel upload path depends on the object store's flexible-checksum feature, which this repository already attempted in every combination and abandoned in favour of completion by entity tag plus a size check — a decision documented at length in the existing uploader. Adopting the library would re-open a closed, hard-won decision in order to obtain concurrency that can be added to the existing uploader directly.
- **Keeping the contiguous-prefix resumption model and only parallelizing the read leg.** Considered as a way to avoid changing the resumption model: fetch several chunks ahead into a buffer, upload in order. Rejected because it either buffers whole chunks — violating the streaming constraint that feature 002's SC-008 exists to enforce — or throttles the read leg to the write leg's pace, which is the bottleneck being removed.
- **Unbounded concurrency, one task per chunk.** Rejected: a payload may have up to ten thousand chunks, and one connection per chunk would exhaust connections, memory, and any provider's tolerance simultaneously.

### Scope boundaries

- Concurrency **within** one payload is the subject of this feature. Concurrency **across** payloads already exists and is not changed, except where the two interact through a shared bound on total connections.
- Feature 002's inline consumption strategy is not touched. This feature applies only to the chunked delivery path.
- Changing the chunk-size derivation, the chunking threshold, or the maximum chunk count is out of scope. Concurrency operates on the chunk plan as it is derived today.
- Migrating transfers that were checkpointed under the contiguous-prefix interpretation is out of scope. An in-flight transfer either completes under the semantics it started with or restarts cleanly — feature 002's FR-032 makes restarting always safe.
- Encryption, compression, and content transformation of payloads remain out of scope.
- Reordering or prioritizing chunks by anything other than position — for example fetching the cheapest chunks first — is out of scope.

### Environment and dependencies

- The provider already accepts a start position and reports the payload's total size when it serves a partial response; this feature extends that to an end position. Range support was built into the provider contract for resumption, and is the enabler that makes concurrency possible at all.
- The provider is an external dependency outside the company's control. Its willingness to serve several concurrent readers at full rate is an assumption this feature's measurement phase exists to verify, not a guarantee.
- The resumption record's store already holds one record per destination object with per-chunk fields and supports refreshing its expiry. This feature changes how the confirmed chunks are interpreted, not where they live, and assumes the store can absorb concurrent confirmations for the same record without losing one.
- The delivery worker's execution model already tolerates work that spends nearly all its life blocked waiting on the network, which is exactly what each concurrent chunk does.
- The maximum permitted chunk count remains ten thousand, and the derived chunk size still scales with payload size. Concurrency is therefore bounded independently of chunk count.
- At-least-once delivery remains the target guarantee, and duplicate delivery remains harmless through deterministic destination naming rather than prevented.
