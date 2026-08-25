# Feature Specification: Staged Consumer with Resumable Chunked Object Upload

**Feature Branch**: `002-staged-resumable-s3-consumer`

**Created**: 2026-08-24

**Status**: Draft

**Input**: User description: "Add a second consumer implementation (keeping both implementations side by side as examples). It should consume in batches, stage the work durably, acknowledge immediately, and have a separate worker perform the object-store write. The object-store write must be streaming and chunked (multipart), and must remember which chunks already succeeded so a retry does not have to redo the whole transfer. Apply the chunked/resumable strategy only to payloads larger than a configurable threshold (e.g. 10G). Payload size may arrive as a message header, or may require probing the object store first — choose whichever is more efficient."

## Overview

The work being consumed is a copy job. A third-party provider signals that a recording has finished and is ready for download from the provider's own servers; the system fetches it and stores it in the company's own object store. Recordings can be very large, the provider is outside the company's control, and both legs of the copy cross the public network — so an interrupted transfer is an expected event, not an exceptional one.

Today the project has exactly one message-consumption strategy: the consumer calls the object store inline, while holding the message, and only acknowledges once that call succeeds or exhausts its retries. That works well for small payloads but couples consumption throughput directly to object-store availability and latency, and it cannot survive a partial transfer — a very large upload that fails at 90% starts again from zero.

This feature adds a **second, independent consumption strategy** alongside the existing one, kept permanently in the codebase as a comparable example. The new strategy decouples intake from delivery: messages are consumed in batches, recorded durably in a staging area, acknowledged immediately, and delivered to the object store later by a separate worker. The worker uploads large payloads as a stream of chunks, records each chunk that lands successfully, and on retry resumes from the first chunk that did not.

Both strategies remain runnable so their trade-offs can be observed and compared directly.

## Clarifications

### Session 2026-08-24

- Q: What does the message actually reference, and where do the payload bytes come from? → A: A third-party provider (Zoom-style) notifies that a recording has finished and is available for download from the provider's own servers; the system copies that recording into the company's own object store. The message carries a reference to the provider-hosted recording, never the bytes.
- Q: Where is the resumable chunk state (confirmed chunks for an in-progress transfer) kept? → A: In a fast key-value cache (Redis), as one hash per destination object keyed by bucket + key, holding each confirmed chunk's ordinal and acknowledgement. The entry carries a sliding expiry refreshed on every chunk confirmation, sized to exceed the maximum retry span, and its loss is always interpreted as "restart the transfer" and never as "the transfer is complete".
- Q: Which payloads get a checkpoint entry, and is that a separate threshold from the chunking one? → A: One threshold, defaulting to 100 MB, governs both. At or above it a payload is chunked *and* checkpointed; below it there is no chunked upload at all, so there is nothing to checkpoint and no entry is written. No second independently configured threshold, which would otherwise create a band where a payload pays the cost of chunking without gaining resumability.
- Q: How does the download leg resume, given that chunk progress is tracked as a single notion covering both download and upload? → A: Chunks are transferred sequentially, so confirmed chunks are always a contiguous prefix. The resume position is therefore derived as confirmed-chunk-count × chunk-size rather than stored as a separate field that could diverge from the chunk record, and the payload is re-requested from the provider starting at that position. If the provider ignores the requested start and serves from the beginning, the leading bytes are discarded so the upload leg still resumes.
- Q: On a retry days later, where does the means of fetching the recording come from? → A: From the staging record. What the record holds is the provider's *stable identifier* for the recording, not the time-limited download credential that arrived with the original notification — a captured credential would be expired by the time a long retry span elapsed. A fresh credential is obtained from the provider at the start of every attempt, and renewed mid-transfer if a single transfer outlives it.
- Q: What happens to the recording at the provider once the copy succeeds? → A: A separate mechanism signals the provider that the source recording may be released, but only once the staged item has moved to its delivered state. Because that signal causes the provider to discard its copy, it must never be sent for an item whose destination object is not finalized and verified — a premature signal destroys the only remaining copy.
- Q: Is the inbound path from the provider (public endpoint, signature verification, publishing to the topic) part of this feature? → A: Yes, in scope. An endpoint separate from the consumer and worker verifies the provider's signature and publishes the notification to the topic, doing no copying, staging, or provider callbacks inline. The intended production deployment of that endpoint is a managed API gateway in front of a serverless handler; the requirement itself is stated in terms of the separation and the verification, not that topology, so it remains satisfiable in a single-process build of this repository.
- Q: Should the retry window be capped at the download token's lifetime? → A: No — rejected. Capping retries at the token's lifetime would subordinate the system's resilience window to an arbitrary provider parameter, defeating the point of tolerating multi-day provider outages. The token is instead minted fresh per attempt and renewed mid-transfer at chunk boundaries, so its lifetime has no bearing on how long an item may be retried.
- Q: With the inbound path now terminating in a handler that could write to the staging store directly, does the topic remain in the flow? → A: Yes, deliberately, and for a reason that is contextual rather than technical: this repository exists to demonstrate and compare Kafka consumption strategies, and this feature's stated purpose is to keep two consumer implementations side by side. The engineering arguments for removing the topic in a production build of this exact pipeline are real and are recorded under Rejected alternatives. A companion feature will specify the topic-free variant so both designs are documented and comparable.
- Q: Does one provider notification correspond to one recording to copy? → A: No. A single "recording finished" notification describes a set of recording files produced by the same session — video, audio, transcript, chat — each with its own identifier, size, and download location. The notification handler publishes one message per recording file, so each file is staged, copied, checkpointed, and released independently.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A very large upload survives an interruption without starting over (Priority: P1)

An operator is archiving a very large payload. Partway through the transfer the object store becomes unreachable, or the service is restarted. When the transfer is retried, it picks up from the last chunk that was confirmed stored, rather than re-sending everything that already succeeded.

**Why this priority**: This is the central problem. For payloads measured in gigabytes, restarting from zero on every failure can mean a transfer never completes at all on an unstable link. Everything else in this feature exists to make this behavior possible and safe.

**Independent Test**: Begin archiving a payload large enough to require multiple chunks; interrupt the transfer after some chunks have been confirmed; restore connectivity and let the retry run. Confirm that the retry re-sends only the chunks that were not previously confirmed, and that the final stored object is complete and byte-identical to the source.

**Acceptance Scenarios**:

1. **Given** a payload large enough to be split into multiple chunks, **When** the transfer is interrupted after some chunks are confirmed stored, **Then** the identifiers of those confirmed chunks are durably retained.
2. **Given** a transfer with confirmed chunks retained, **When** the transfer is retried, **Then** only the chunks that were never confirmed are transferred again.
3. **Given** all chunks for a payload are confirmed, **When** the transfer completes, **Then** the payload is finalized as a single complete object in the object store and the retained chunk records are released.
4. **Given** a transfer is retried after a full service restart (not merely an in-process retry), **When** the retry begins, **Then** it still resumes from the previously confirmed chunks rather than starting over.
5. **Given** a transfer is resumed, **When** the payload is re-requested from the provider, **Then** it is requested from the resume position rather than from the beginning, so the download is not repeated either.
6. **Given** the provider ignores the requested start position and serves the payload from the beginning, **When** the transfer resumes, **Then** the leading bytes are discarded and only unconfirmed chunks are uploaded, so the upload leg is still not repeated.

---

### User Story 2 - Intake keeps running while the object store is degraded (Priority: P1)

Messages continue to arrive while the object store is slow or unavailable. The new consumer records them durably and acknowledges them right away, so intake does not stall. The backlog of undelivered work is visible, and delivery catches up on its own once the object store recovers.

**Why this priority**: This is the other half of what makes the second implementation worth having, and it is what distinguishes it from the existing inline consumer. Without it, the new consumer is just a slower version of the old one. It is testable and valuable on its own even before resumable chunking exists.

**Independent Test**: Make the object store unavailable, publish a steady stream of messages, and confirm the consumer keeps consuming and acknowledging at full rate with no growth in unconsumed messages on the topic; then restore the object store and confirm the staged backlog drains without any manual action.

**Acceptance Scenarios**:

1. **Given** the object store is unavailable, **When** messages arrive, **Then** they are recorded in the staging area and acknowledged, and consumption continues at its normal rate.
2. **Given** messages are being staged, **When** a message is acknowledged, **Then** its staging record is already durably committed — acknowledgement never precedes durable staging.
3. **Given** a staged backlog exists, **When** the object store becomes healthy again, **Then** the worker delivers the backlog without operator intervention.
4. **Given** the object store stays unavailable long enough for the staged backlog to reach its configured capacity limit, **When** the limit is reached, **Then** the consumer stops taking new messages until the backlog drains below the limit.

---

### User Story 3 - The source is released only once our copy is safe (Priority: P1)

Once a recording has been copied successfully, the provider is told it may release its own copy so the recording is not stored twice indefinitely. That signal is sent only after the copy is finalized and verified — because the moment it is sent, the provider's copy is gone and ours is the only one left.

**Why this priority**: This is the only irreversible, outward-facing action in the feature. Every other failure mode costs time or bandwidth; this one costs the recording permanently. It ranks alongside the two other P1 stories because getting it wrong turns a recoverable incident into unrecoverable data loss.

**Independent Test**: Inject a failure at each stage of the copy — mid-chunk, before finalization, and during finalization — and confirm that in every case no release signal reaches the provider. Then let a copy complete normally and confirm the signal is sent exactly once.

**Acceptance Scenarios**:

1. **Given** a staged item that has not reached its delivered state, **When** the release mechanism runs, **Then** no release signal is sent to the provider.
2. **Given** a transfer that failed during finalization, **When** the release mechanism runs, **Then** no release signal is sent, and the item remains eligible for retry.
3. **Given** a staged item whose destination object is finalized and verified, **When** the release mechanism runs, **Then** exactly one release signal is sent to the provider.
4. **Given** a release signal that fails to reach the provider, **When** it is retried, **Then** the retry is harmless and the item's delivered state is not reverted and the payload is not re-copied.
5. **Given** items that are delivered but whose release signal has not yet succeeded, **When** an operator inspects system status, **Then** those items are visible as a distinct condition rather than being indistinguishable from fully completed ones.

---

### User Story 4 - Both consumption strategies coexist as comparable examples (Priority: P2)

A developer studying the project can run either the original inline strategy or the new staged strategy, see both in the codebase at the same time, and compare their behavior under identical conditions without editing code.

**Why this priority**: The explicit intent is to keep both implementations as reference examples. This is what turns the work into a teaching artifact rather than a replacement. It depends on the new strategy existing first, so it ranks below the two P1 stories.

**Independent Test**: With no code changes, switch which strategy is active via configuration, run each in turn under an identical object-store outage and recovery, and confirm their distinct acknowledgement and backlog behaviour is directly observable while each still delivers everything it acknowledged.

**Acceptance Scenarios**:

1. **Given** both strategies are present, **When** the active strategy is selected by configuration, **Then** only the selected strategy consumes messages and the other remains idle.
2. **Given** each strategy is run in turn under an identical object-store outage and recovery, **When** both are observed, **Then** their differing acknowledgement and backlog behaviour is directly visible — the inline strategy pauses and accrues consumer lag, the staged strategy keeps consuming and accrues staged backlog — and each still delivers every message it acknowledged.
3. **Given** either strategy is active, **When** the application starts, **Then** it starts cleanly with no errors caused by the inactive strategy being present.

---

### User Story 5 - Small payloads are not penalized by chunking machinery (Priority: P2)

Most payloads are small. Those are delivered by a single straightforward transfer, without the extra bookkeeping, extra round-trips, and orphaned-state risk that chunked uploads carry. Only payloads above a configured size use the chunked, resumable path.

**Why this priority**: Chunked transfers add per-payload overhead and leave recoverable state behind that must be tracked and cleaned up. Applying them indiscriminately would make the common case slower and messier. Valuable, but the feature is still correct without the optimization.

**Independent Test**: Archive a payload below the configured threshold and one above it; confirm the small one is delivered as a single transfer with no chunk bookkeeping created, and the large one uses the chunked path.

**Acceptance Scenarios**:

1. **Given** a payload whose size is below the configured threshold, **When** it is delivered, **Then** it is sent as a single streaming transfer and no chunk-tracking records are created.
2. **Given** a payload whose size is at or above the configured threshold, **When** it is delivered, **Then** it is sent as chunks with per-chunk confirmation tracking.
3. **Given** the threshold is changed in configuration, **When** the service restarts, **Then** the new threshold governs subsequent delivery decisions.

---

### User Story 6 - Payload size is known without paying for an extra round-trip (Priority: P3)

The delivery worker decides between the single-transfer path and the chunked path based on the payload's size. That size is taken from information already travelling with the message, so no additional network call is needed just to make the decision. When that information is missing or turns out to be wrong, delivery still produces a correct result.

**Why this priority**: This is an efficiency and robustness refinement of the threshold decision in User Story 4. A correct but less efficient implementation (always probing, or always chunking) would still satisfy the earlier stories.

**Independent Test**: Publish a message carrying a declared size and confirm the correct path is chosen with no size-probe call made; then publish one with the size omitted, and one with a size that understates the true payload, and confirm both still result in a complete, correct stored object.

**Acceptance Scenarios**:

1. **Given** a message that declares its payload size, **When** the delivery path is selected, **Then** the declared size is used and no extra call is made to determine size.
2. **Given** a message that does not declare its payload size, **When** the delivery path is selected, **Then** the size is determined by a single metadata lookup, and the result is recorded so the lookup is not repeated on retry.
3. **Given** a declared size that is smaller than the payload actually turns out to be, **When** the payload exceeds what a single transfer can carry during streaming, **Then** delivery transparently switches to the chunked path and still completes successfully.
4. **Given** a declared size that cannot be trusted (negative, non-numeric, or absurdly large), **When** it is read, **Then** it is ignored in favour of a metadata lookup rather than being used as-is.

---

### User Story 7 - Abandoned partial transfers do not accumulate silently (Priority: P3)

Chunked transfers that are never finished leave partial data occupying storage and incurring cost. The system reclaims that space on a predictable schedule, and operators can see how much unfinished transfer state exists.

**Why this priority**: A real operational cost and a well-known failure mode of chunked uploads, but it does not affect functional correctness of successful transfers.

**Independent Test**: Start a chunked transfer, abandon it permanently, and confirm that after the configured retention window the partial state is reclaimed and reported.

**Acceptance Scenarios**:

1. **Given** a chunked transfer whose staging record has been permanently abandoned, **When** the configured retention window elapses, **Then** its partial state is discarded and the associated storage is reclaimed.
2. **Given** a chunked transfer is still within its retention window and eligible for retry, **When** cleanup runs, **Then** its partial state is preserved so resumption remains possible.
3. **Given** unfinished transfer state exists, **When** an operator inspects system status, **Then** the count and total size of unfinished transfers is reported.

---

### Edge Cases

- **Duplicate delivery**: The same message is staged twice (redelivery before acknowledgement was recorded). Because acknowledgement follows durable staging, a crash in between causes redelivery. Delivery must be idempotent — the same source event must resolve to the same destination object name, so a repeat delivery overwrites rather than duplicates.
- **Interrupted mid-chunk**: A failure occurs while a chunk is in flight and it is unknown whether the object store accepted it. That chunk must be treated as unconfirmed and re-sent; re-sending an already-accepted chunk must be harmless.
- **Two workers pick up the same staged item**: Concurrent delivery attempts on one payload must not both drive the same chunked transfer, or chunk bookkeeping will diverge. A staged item must be claimed exclusively for the duration of an attempt, and a claim must expire if its holder dies.
- **Resumption state outlives the transfer's viability**: The object store discards partial chunk state, or the source recording changes at the provider. Resuming against stale state would produce a corrupt object; the transfer must detect this and restart cleanly rather than finalize something wrong.
- **Checkpoint store is emptied or unavailable**: The checkpoint store is flushed, fails over to an empty replica, or is unreachable. Every affected transfer must restart from the beginning — correct, just wasteful. The dangerous misreading is the opposite one: treating "no unconfirmed chunks recorded" as "all chunks confirmed" and finalizing an empty or partial object. Completion is decided by the staged item's state, never by checkpoint absence.
- **Checkpoint expires beneath a transfer that is still progressing**: A single very large recording takes longer to transfer than the configured expiry. A fixed expiry set at creation would delete the resumption state mid-flight; the expiry must slide forward on every confirmed chunk.
- **Recording disappears at the provider mid-retry**: The recording is deleted at the provider between staging and a later retry. Largely mitigated because release is driven by our own signal, which is withheld until the copy is verified — so this should only occur through provider-side action outside our control. When it does, the transfer must terminate as permanently failed with that reason recorded, rather than retrying against something that will never return.
- **Release signal sent for an unfinalized object**: The item is marked delivered but finalization actually failed, or finalized a truncated object. This is the one unrecoverable failure in the feature — the provider discards its copy and no complete copy exists anywhere. Verification must gate the signal, not the delivered flag alone.
- **Release signal succeeds but its outcome is not recorded**: A crash between signalling and recording the outcome causes the signal to be re-sent. Re-sending must be harmless; the provider having already released the recording is a success, not a failure.
- **Download credential expires mid-transfer**: A single very large recording takes longer to fetch than its credential is valid for. The credential must be renewed and the fetch continued from the resume position, not restarted.
- **Threshold set above what a single transfer can carry**: The object store imposes a hard maximum on a single-request upload. A threshold configured above that maximum would route payloads into a path that cannot carry them. The configured threshold must be validated against that maximum at startup.
- **Chunk count ceiling**: Chunked uploads have a maximum number of chunks. For a very large payload, a small chunk size would exceed it. Chunk size must be derived from the payload size so the ceiling is never hit.
- **Minimum chunk size**: All chunks except the last must meet a minimum size. A payload just over the threshold must not produce an undersized non-final chunk.
- **Staging area unavailable**: The staging area itself fails. The consumer must not acknowledge messages it could not stage; those messages must be redelivered rather than silently lost.
- **Backlog capacity reached while a batch is in flight**: The capacity limit is crossed midway through staging a batch. The in-flight batch must be resolved consistently — fully staged and acknowledged, or fully rejected — never half-acknowledged.
- **Source stream shorter than declared**: The payload ends before the declared size. The transfer must fail cleanly and not finalize a truncated object.
- **Poison payload**: A payload fails delivery repeatedly for reasons that will never resolve. It must eventually stop being retried and be routed to a terminal failed state with its reason retained, rather than blocking the backlog forever.

## Requirements *(mandatory)*

### Functional Requirements

#### Second consumption strategy

- **FR-001**: The system MUST provide a second message-consumption strategy that stages consumed messages durably and acknowledges them independently of object-store delivery.
- **FR-002**: The system MUST retain the existing inline consumption strategy unchanged and runnable, so both strategies exist side by side as reference examples.
- **FR-003**: The system MUST allow selecting which consumption strategy is active through configuration, without code changes, with exactly one strategy consuming a given message stream at a time.
- **FR-004**: The system MUST start cleanly with either strategy selected, with the inactive strategy causing no errors, warnings, or resource consumption.
- **FR-005**: Both strategies MUST be directly comparable under identical load: each MUST expose the same observable
  behaviours — when a message is acknowledged relative to the object-store write, how backlog accumulates, and whether
  consumption pauses — so the trade-off between them can be observed rather than inferred. The two strategies carry
  different work (the inline strategy archives an event; the staged strategy copies a provider-hosted recording), so
  equivalence is behavioural and MUST NOT be read as producing identical objects.

#### Batched intake and staging

- **FR-006**: The staged strategy MUST consume messages in batches, bounded by both a maximum batch size and a maximum wait time, whichever is reached first.
- **FR-007**: The system MUST durably record each consumed message in the staging area before acknowledging it.
- **FR-008**: The system MUST NOT acknowledge any message whose staging record was not durably committed.
- **FR-009**: The system MUST record staged messages from one consumed batch as a single atomic unit — either all are staged and the batch is acknowledged, or none are and the batch is redelivered.
- **FR-010**: The system MUST track a delivery state for each staged item that distinguishes at minimum: awaiting delivery, delivery in progress, delivered, and permanently failed.

#### Backlog control

- **FR-011**: The system MUST expose the current staged backlog size (count of items awaiting delivery).
- **FR-012**: The system MUST stop consuming new messages when the staged backlog reaches a configured upper limit, and resume consuming when it falls below a configured lower limit.
- **FR-013**: The system MUST keep the message-stream connection alive while consumption is stopped, so that stopping does not trigger a consumer-group reassignment.
- **FR-014**: The system MUST record a clearly identifiable status change whenever consumption is stopped or resumed, including the reason and the backlog size at that moment.

#### Asynchronous delivery worker

- **FR-015**: The system MUST deliver staged items to the object store from execution separate from message consumption, so that delivery latency does not affect consumption rate.
- **FR-016**: The delivery worker MUST claim a staged item exclusively before attempting delivery, so that concurrent workers never attempt the same item simultaneously.
- **FR-017**: The system MUST release a claim whose holder has stopped making progress within a configured timeout, so that a crashed worker does not strand an item permanently.
- **FR-018**: The system MUST retry failed deliveries with an increasing delay between attempts, up to a configured maximum number of attempts.
- **FR-019**: The system MUST move an item to a permanently failed state after its retry attempts are exhausted, retaining the failure reason, and MUST NOT let it block delivery of other items.
- **FR-020**: The system MUST apply object-store failure detection (the existing circuit-breaking behavior) to the delivery worker's calls rather than to the consumption path, since the consumption path no longer contacts the object store.

#### Streaming and chunked upload

- **FR-021**: The system MUST write payloads to the object store as a stream, without holding the complete payload in memory.
- **FR-022**: The system MUST bound the memory used per in-flight transfer regardless of payload size.
- **FR-023**: The system MUST use a chunked, multi-request upload for payloads at or above a single configured size threshold, defaulting to 100 MB, and a single streaming request below it.
- **FR-024**: That same threshold — and no second, independently configured one — MUST govern whether a checkpoint entry is created, so that a payload is either chunked *and* checkpointed or neither.
- **FR-025**: The configured threshold MUST be validated at startup against the object store's maximum single-request upload size, and startup MUST fail with a clear message if the threshold exceeds it.
- **FR-026**: The system MUST derive chunk size from payload size so that neither the object store's maximum chunk count nor its minimum chunk size constraint is violated.

#### Resumable chunk tracking

- **FR-027**: For every chunked upload, the system MUST record the upload's identity and its per-chunk progress in a checkpoint store shared by all workers and external to any single worker's memory.
- **FR-028**: For each confirmed chunk, the checkpoint entry MUST record both the chunk's ordinal position and the acknowledgement token the object store returned for it. Both are required by the finalization call, so recording ordinals alone is insufficient to finalize the object.
- **FR-029**: Checkpoint entries MUST survive a full service restart, so that resumption works across restarts and not merely across in-process retries.
- **FR-030**: The checkpoint store MUST key each entry by the destination object's identity (its container and name), so that any worker can locate the resumption state for a given destination without consulting another system.
- **FR-031**: On retrying a chunked upload, the system MUST resume from the first unconfirmed chunk and MUST NOT re-transfer chunks already confirmed.
- **FR-032**: The system MUST treat a missing or expired checkpoint entry as an instruction to restart the transfer from the beginning, and MUST NEVER interpret it as evidence that the transfer completed.
- **FR-033**: The authoritative record of whether an item has been delivered MUST be the staged item's delivery state, never the presence or absence of a checkpoint entry.
- **FR-034**: The system MUST verify that retained resumption state is still valid at the object store before resuming, and MUST restart the upload from the beginning if the object store no longer recognises it.
- **FR-035**: The system MUST finalize a chunked upload into a single complete object only after every chunk is confirmed.
- **FR-036**: The system MUST release the checkpoint entry once the upload is finalized.
- **FR-037**: The system MUST treat a chunk whose acceptance is unknown (interrupted in flight) as unconfirmed, and re-sending such a chunk MUST be harmless.

#### Checkpoint expiry

- **FR-038**: Each checkpoint entry MUST carry an expiry that is refreshed on every chunk confirmation, so that a transfer still making progress never has its resumption state expire beneath it.
- **FR-039**: The checkpoint expiry MUST be configurable and MUST be validated at startup to be greater than the maximum possible retry span (maximum attempts multiplied by maximum backoff), so that a transfer still eligible for retry cannot lose its resumption state.
- **FR-040**: The checkpoint expiry MUST be validated at startup to be shorter than the object store's configured window for reclaiming unfinished chunked uploads, so that a checkpoint entry can never reference partial state the object store has already discarded.
- **FR-041**: Startup MUST fail with a clear message when either expiry-ordering constraint in FR-039 or FR-040 is violated.

#### Source-side resume

- **FR-042**: The system MUST transfer chunks sequentially, so that the set of confirmed chunks is always a contiguous prefix of the payload.
- **FR-043**: The system MUST derive the byte position to resume reading from as the count of confirmed chunks multiplied by the chunk size, and MUST NOT maintain a separately stored read offset that could diverge from the chunk record.
- **FR-044**: The system MUST request the payload from the provider starting at the derived resume position, rather than re-reading the payload from its start.
- **FR-045**: The system MUST detect when the provider ignores the requested start position and serves the payload from the beginning, and MUST discard the bytes preceding the resume position so that already-confirmed chunks are still not re-uploaded.
- **FR-046**: The system MUST classify provider-side failures separately from object-store failures, so that an unhealthy provider does not trip the object store's failure detection or vice versa.

#### Payload size determination

- **FR-047**: The system MUST read the payload size from metadata accompanying the message when it is present, and MUST NOT make an additional call to the provider in that case.
- **FR-048**: The system MUST reject an accompanying size value that is absent, non-numeric, negative, or beyond a configured plausible maximum, and fall back to a single metadata lookup against the provider.
- **FR-049**: The system MUST record a size obtained by metadata lookup alongside the staged item, so the lookup is performed at most once per item across all retries.
- **FR-050**: The system MUST handle a declared size that proves incorrect during streaming by switching to the chunked path if the payload exceeds what a single request can carry, so the transfer still completes.
- **FR-051**: The system MUST fail the transfer without finalizing an object when the payload stream ends before the declared size is reached.

#### Idempotency and correctness

- **FR-052**: The system MUST derive each destination object's name deterministically from the individual recording file's identifier, not from the notification or session alone, so that the several files of one session cannot collide and so that redelivering the same file overwrites rather than duplicating.
- **FR-053**: The system MUST NOT expose a partially written object to readers — an object becomes visible only when complete.
- **FR-054**: A finalized object MUST be byte-identical to the payload served by the provider.

#### Cleanup and observability

- **FR-055**: The system MUST reclaim partial upload state for transfers that have been permanently abandoned, after a configured retention window that is longer than the maximum possible retry span.
- **FR-056**: The system MUST NOT reclaim partial upload state for a transfer that is still eligible for retry.
- **FR-057**: The system MUST report the count and total size of unfinished chunked transfers, including those whose checkpoint entry has expired and which are therefore no longer resumable.
- **FR-058**: The system MUST report, for the staged strategy: backlog size, delivery throughput, retry counts, permanently failed counts, and checkpoint-store availability.

#### Provider access and credentials

- **FR-059**: The staged item MUST hold the provider's stable identifier for the recording, and the system MUST NOT depend on a time-limited download credential captured at notification time as the means of fetching the recording later.
- **FR-060**: The system MUST obtain a fresh download credential from the provider at the start of every delivery attempt.
- **FR-061**: The system MUST renew the download credential during a transfer that outlives it, continuing from the derived resume position rather than restarting the transfer.
- **FR-062**: The system MUST verify that the download target belongs to an allowlisted provider domain before fetching, so that a manipulated or spoofed reference cannot direct the fetch at an arbitrary host.
- **FR-063**: Credentials for reading from the provider and credentials for writing to the company's object store MUST be separate, and object-store write credentials MUST NEVER be sourced from message content or from any provider-supplied field.

#### Source release signal

- **FR-064**: The system MUST signal the provider that the source recording may be released only after the destination object has been finalized and its integrity verified.
- **FR-065**: The system MUST NOT send the release signal for a staged item in any state other than delivered.
- **FR-066**: The release signal MUST be idempotent and safely retryable, so that neither a lost signal nor a duplicated one leaves the recording unreleasable or prematurely destroyed.
- **FR-067**: The system MUST record the outcome of each release signal and MUST surface, as a distinct condition, items that are delivered but whose release signal has not yet succeeded.
- **FR-068**: A failure to send the release signal MUST NOT revert the item's delivered state and MUST NOT cause the payload to be copied again.

#### Provider notification ingress

- **FR-069**: The system MUST expose an HTTPS endpoint that receives provider notifications, verifies them, and publishes them to the topic — and nothing else. The endpoint MUST be separable from the consumer and delivery worker, so that public exposure and copy processing can be deployed, scaled, and secured independently.
- **FR-070**: The system MUST verify the provider's cryptographic signature over each notification before any other processing, and MUST reject notifications that fail verification without publishing anything.
- **FR-071**: Signature comparison MUST be constant-time, so that response timing does not reveal how much of a candidate signature was correct.
- **FR-072**: The system MUST reject notifications whose signed timestamp falls outside a configured freshness window, so that a captured notification cannot be replayed later. Including the timestamp in the signed material is not sufficient on its own — the value itself MUST be range-checked against the current time.
- **FR-073**: The system MUST answer the provider's one-time endpoint-validation challenge issued when the endpoint is registered.
- **FR-074**: The notification handler MUST do nothing beyond verification and publishing before responding, so that it answers within the provider's acknowledgement timeout and does not trigger provider-side retries or endpoint suspension.
- **FR-075**: The handler MUST attach the provider-declared recording size to the published message as metadata, so the delivery worker can select its upload path without an extra lookup.
- **FR-076**: The handler MUST attach the provider's event identifier to the published message, so that duplicate notifications caused by provider retries are detectable downstream.
- **FR-077**: The handler MUST publish one message per recording file described by a notification, rather than one message per notification, so that each file is staged, copied, and released independently.
- **FR-078**: The handler MUST publish every file of a notification, or none, before acknowledging it, so that a partial publish cannot be recorded as a delivered notification.
- **FR-079**: The handler MUST acknowledge the notification to the provider only after the published messages are durably accepted by the topic. A failure to publish MUST result in a response that causes the provider to retry, never in a success response.
- **FR-080**: The handler MUST distinguish a permanently unprocessable notification from a transient failure, responding so that the provider stops retrying the former and continues retrying the latter.
- **FR-081**: The handler MUST NOT perform the copy, write to the staging store, or call back to the provider inline.
- **FR-082**: The secret used to verify notification signatures MUST be held in managed secret storage, never in message content, configuration files, or code.

### Key Entities

- **Recording Reference**: What the message actually carries — the provider's identity for a finished recording, the information needed to fetch it from the provider, and optionally its declared size. Never the recording's bytes.
- **Staged Item**: One consumed message recorded durably and awaiting delivery. Carries the recording reference, the deterministic destination name, the known or discovered size, the delivery state, attempt count, last failure reason, and the claim held by any worker currently delivering it. **This is the authoritative record of whether the item still needs delivering.**
- **Transfer Checkpoint**: The resumption state for one in-progress chunked upload, held in the shared checkpoint store as a single entry keyed by the destination object's container and name. Carries the object store's identifier for the upload, the chosen chunk size, the total chunk count, and the confirmation for each chunk already stored. Carries a sliding expiry refreshed on every chunk confirmation. Exists only for payloads at or above the chunking threshold. **Disposable by design** — its absence means "restart", never "done" — and released on finalization.
- **Chunk Confirmation**: Evidence that one specific chunk of a transfer is stored — its ordinal position paired with the acknowledgement token the object store returned for it. Both halves are required by the finalization call, so an ordinal without its token is not a usable confirmation. One field within a Transfer Checkpoint.
- **Resume Position**: Not stored. Derived on demand as confirmed-chunk-count × chunk size, which is exact because chunks are transferred sequentially and confirmed chunks therefore form a contiguous prefix. Serves both legs of the copy: where to resume reading from the provider, and which chunk to upload next.
- **Backlog State**: The aggregate view of staged items awaiting delivery that drives the decision to stop or resume consumption, and that operators observe.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A very large transfer interrupted after 90% of its chunks are confirmed completes on retry by moving approximately 10% of the payload across **both** legs — roughly 10% downloaded from the provider and 10% uploaded to the object store, not 100% of either.
- **SC-002**: Against a provider that refuses to serve from a requested start position, the same interrupted transfer still uploads only the missing ~10%, even though it must re-read 100% from the provider.
- **SC-003**: A transfer resumes correctly after a full service restart, not merely after an in-process retry.
- **SC-004**: While the object store is completely unavailable, the staged strategy continues consuming and acknowledging messages at its normal rate, and the count of unconsumed messages on the topic does not grow.
- **SC-005**: After the object store recovers from an outage, the staged backlog drains to zero with no operator intervention.
- **SC-006**: No acknowledged message is ever absent from both the staging area and the object store — zero message loss across induced crashes at every stage boundary.
- **SC-007**: Every finalized object is byte-identical to its source payload, verified for payloads spanning below-threshold, just-above-threshold, and many-chunk sizes.
- **SC-008**: Peak heap attributable to one in-flight transfer stays under 32 MB and does not grow with payload size — measured across payloads differing by at least three orders of magnitude, with the largest payload's peak within 2x of the smallest's.
- **SC-009**: Payloads below the configured threshold are delivered with no chunk-tracking records created and no additional round-trips versus the existing inline strategy.
- **SC-010**: When size accompanies the message, delivery makes zero extra calls to determine size.
- **SC-011**: Switching between the two strategies requires only a configuration change and a restart — no code modification.
- **SC-012**: Under an identical induced object-store outage, the two strategies exhibit measurably different and correct behaviour — the inline strategy's consumer pauses and topic lag grows; the staged strategy's consumption rate is unchanged and its staged backlog grows instead — and after recovery both have delivered every message they acknowledged, with zero loss on either path.
- **SC-013**: Stopping consumption due to backlog pressure never causes a consumer-group reassignment.
- **SC-014**: Partial state from permanently abandoned transfers is fully reclaimed within the configured retention window, leaving no unbounded growth in storage cost.
- **SC-015**: A payload that fails delivery permanently does not delay delivery of unrelated payloads behind it in the backlog.
- **SC-016**: Emptying the checkpoint store mid-transfer costs re-transfer and nothing else — no object is finalized incomplete, no acknowledged recording goes missing, and every affected transfer still completes correctly.
- **SC-017**: A transfer whose duration exceeds the configured checkpoint expiry still completes without losing its resumption state, confirming the expiry slides with progress.
- **SC-018**: Across fault injection at every stage of the copy — mid-chunk, before finalization, and during finalization — the provider never receives a release signal for a recording whose destination object is not finalized and verified. Zero premature releases.
- **SC-019**: A transfer whose duration exceeds the lifetime of its download credential completes without restarting from the beginning.
- **SC-020**: A retry attempted after the original notification's download credential would have expired still succeeds, confirming the staged item does not depend on that captured credential.
- **SC-021**: A notification with a missing, incorrect, or stale signature is rejected and results in nothing being published to the topic.
- **SC-022**: The notification handler answers within 3 seconds at p99 while sustaining 50 notifications per second — a margin against the 3-second acknowledgement timeout typical of this class of provider — so the provider never retries or suspends the endpoint for slowness.
- **SC-023**: A notification delivered more than once by the provider results in exactly one stored object and exactly one release signal.

## Assumptions

### Decisions made where the description left a choice

- **Size is taken from message metadata, not from probing the object store.** The description explicitly left this choice open. Reading a value that already travels with the message costs nothing, whereas probing costs a full network round-trip per payload — against the very service whose instability this feature exists to tolerate, and on the path that a large backlog will traverse thousands of times. A metadata lookup is therefore used only as a fallback when the accompanying value is missing or implausible, and its result is persisted so the cost is paid at most once per item rather than once per retry. Because a declared size is a hint that can be wrong, the streaming writer must also tolerate the payload exceeding it (FR-050).
- **The configured threshold cannot be 10 GB as literally suggested.** Object stores cap a single-request upload well below that (commonly 5 GB), so a 10 GB threshold would route every payload between the cap and 10 GB into a path physically unable to carry it. The threshold is therefore configurable but validated against that cap at startup (FR-025), and defaults to 100 MB — the point where chunking begins to pay for itself.
- **Checkpoint-store footprint is not a reason to raise the threshold.** A checkpoint entry costs roughly the chunk count multiplied by a small per-chunk record: a 10 GB recording in 100 MB chunks is on the order of a hundred chunks, and even the maximum permitted chunk count keeps a single entry around a megabyte. Thousands of concurrent large transfers therefore remain a negligible footprint. What can genuinely grow without bound is the *number* of entries, which is why every entry carries an expiry (FR-038) and is released on finalization (FR-036) — not why the threshold exists.

### Rejected alternatives

- **Removing the topic and writing staged rows straight from the notification handler.** Technically the stronger design for this pipeline, and rejected only on grounds of the repository's purpose. The topic here is a queue in front of a queue: the staging table already provides the durability, retry state, and per-item claim semantics that make up the real work queue, and the consumer does nothing but drain into it. The usual justifications do not survive scrutiny at this scale — a dead-letter queue covers a staging-store outage far more cheaply than a broker; connection pooling covers notification bursts; and no second consumer of the event exists to justify fan-out. Partition-based parallelism is in fact a poor fit for this workload, since transfers vary from minutes to hours and a single very large recording would block its partition. The topic is retained because demonstrating and comparing consumer strategies is this repository's subject matter, and the topic-free variant is specified separately rather than discarded.
- **Capping the retry window at the download token's lifetime.** Considered as a way to avoid renewing credentials, and rejected: it ties how long the system will keep trying to an arbitrary provider-side parameter, so a provider outage lasting longer than a token lifetime would permanently fail items whose recordings are still perfectly available. Minting per attempt (FR-060) and renewing mid-transfer (FR-061) removes the coupling entirely at far lower cost.

### Scope boundaries

- A companion feature specifies the same copy pipeline without the topic, with the notification handler writing staged rows directly. The two are deliberate alternatives, not a migration path; neither supersedes the other.
- The inbound notification path is in scope, but only as far as the topic: verification and publishing. Everything the provider does before that, and every consumer of the topic other than this feature's, is outside it.
- The existing inline consumption strategy is not modified; it is preserved as-is as the comparison baseline. Only the object-store failure-detection wiring is repointed so it guards the new delivery worker's calls (FR-020).
- Exactly one strategy consumes a given message stream at a time. Running both concurrently against the same stream is out of scope — it would either split messages between them or double-write the same objects, and neither makes a useful example.
- Chunk-level parallelism (transferring several chunks of one payload concurrently) is out of scope for this feature; chunks are transferred sequentially. Resumability is the goal, not peak single-payload throughput.
- Encryption, compression, and content transformation of payloads are out of scope.
- Migrating already-staged items across a change of chunk size or threshold is out of scope; in-flight transfers complete under the settings they started with, or restart cleanly.

### Environment and dependencies

- The requirements deliberately describe the notification endpoint by its obligations — separable from the copy path, verifies before publishing, returns within the provider's timeout — rather than by a deployment topology. The intended production shape is a managed API gateway in front of a serverless handler. In this repository, which runs as a single service against a local broker with no cloud deployment path, the same obligations are satisfiable by a request handler within the service, provided it stays free of copy, staging, and provider-callback work. Planning may choose either without contradicting the spec.

- The payload's bytes originate at a third-party provider, not inside the message. The provider signals that a recording has finished and is available for download from its own servers; the system's job is to copy that recording into the company's own object store. The message therefore carries a reference to the provider-hosted recording plus optionally its declared size.
- The provider is an external dependency outside the company's control, with its own availability, throttling, retention, and credential-lifetime behavior. Its failure modes are distinct from the object store's and are not covered by the object-store failure detection in FR-020.
- A durable staging store is available to the service and is materially more available than the object store. If the staging store is down, the staged strategy cannot accept messages at all (FR-008) — it trades a dependency on the object store for a dependency on the staging store, which is only a win if that assumption holds.
- A shared, expiring key-value checkpoint store is available to all delivery workers, is fast enough to absorb a write per confirmed chunk without becoming the bottleneck, and supports per-entry expiry that can be refreshed. It is explicitly permitted to lose data: FR-032 and FR-033 make its loss cost a restarted transfer rather than a lost or corrupted object.
- Recording sizes fall comfortably within the object store's hard limits, so no recording needs splitting across multiple destination objects. The governing constraint is not the maximum object size but the maximum chunk count, which is why chunk size must be derived from payload size (FR-026) rather than fixed. Concretely, for the target object store: maximum object size 5 TB; maximum single-request upload 5 GB; maximum 10,000 chunks per upload; chunk size between 5 MB and 5 GB, with only the final chunk permitted below the minimum. Reaching the 5 TB ceiling would require chunks of roughly 537 MB.
- The object store retains unfinalized partial uploads until they are explicitly reclaimed — they do not expire on their own and they continue to incur storage cost, which is why an explicit reclamation window is required (FR-055) and why the checkpoint expiry must be ordered against it (FR-040).
- At-least-once delivery is the target guarantee. Exactly-once is not claimed; duplicate delivery is made harmless by deterministic destination naming (FR-052) rather than prevented.
- Existing message publishing is assumed to be extended to attach the declared payload size where the publisher knows it; messages without it remain fully supported via the fallback path.
