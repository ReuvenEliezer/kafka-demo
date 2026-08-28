# Parallel ranged transfer — design notes (pre-spec)

**Status:** design notes only. Not a spec-kit artifact yet — no `spec.md` / `tasks.md`.
Run `/speckit-specify` to formalize before implementing phases 1–4.

**Goal:** cut wall-clock transfer time for large payloads by fetching and uploading multipart
chunks **in parallel** instead of strictly sequentially.

---

## Why this is a spec change, not a refactor

`ChunkedUploader` transfers parts strictly in order: download part N, upload part N, confirm,
then N+1. Total time is the **sum** of all parts, capped at one connection's bandwidth.

Two documented decisions block a naive parallelization:

1. **FR-042 mandates sequential transfer.** `TransferCheckpoint.confirmedPrefixLength()` computes
   the *contiguous prefix* of confirmed parts, and its javadoc states: "Sequential transfer (FR-042)
   should make gaps impossible; computing the prefix means a gap costs a re-transfer, never a
   corrupt object." Parallel completion creates gaps by construction (part 5 finishing before
   part 3), which makes `resumeBytePosition()` return a wrong offset.

2. **SC-008 forbids buffering a whole part.** Bytes must move provider-socket → bounded stream →
   S3-socket. Parallelism must not turn into "buffer N parts in memory".

## What is NOT the answer: S3TransferManager / CRT

Rejected after reading `ChunkedUploader`'s class javadoc. `S3TransferManager` over
`S3AsyncClient.crtBuilder()` depends on AWS **flexible checksums**, which this repo already tried
in every combination and abandoned — see the javadoc at `ChunkedUploader.java:56-70` and the
`localstack-s3-checksums` memory. The repo deliberately settled on **ETag-only completion**.
Adopting TransferManager would re-open a closed, hard-won decision.

**Keep the existing `ChunkedUploader` and parallelize it.**

---

## Prerequisite that already exists

`HttpProviderClient.openDownload(id, credential, fromByte)` already sends
`Range: bytes=<fromByte>-` and handles `206 Partial Content`, parsing `Content-Range` for total
size (`HttpProviderClient.java:82`). Range support is the enabler for parallel fetch — it is
already in the provider contract, built for *resume*.

**Caveat:** range support is not guaranteed at runtime. `ProviderDownload.rangeHonoured` is `false`
when the provider returns `200` instead of `206`, and FR-045 requires the caller to discard leading
bytes itself (`skipFully`). See phase 4.

---

## Phases

### Phase 0 — measure first (do this before anything else)

Instrument per-part throughput. **If the provider's outbound bandwidth is the bottleneck, parallelism
buys nothing and phases 1–4 are all waste.** This phase is cheap and can cancel the entire project.

Deliverable: per-part timing + bytes/sec, split by "time spent reading from provider" vs "time spent
writing to S3".

### Phase 1 — bounded range in the provider contract

Today's `bytes=N-` is open-ended, which is correct for resume but wrong for parallelism: N concurrent
connections would each stream to end-of-file, costing N× the bandwidth.

- `ProviderClient.openDownload(id, credential, fromByte)` → add `toByte`.
- Update `HttpProviderClient` to emit `bytes=<from>-<to>`.
- Update `FakeProviderServer` in tests to honor bounded ranges.
- `BoundedInputStream` still caps client-side, but the request itself must carry the upper bound.

### Phase 2 — checkpoint data model: contiguous prefix → sparse set

- `TransferCheckpoint.confirmedPrefixLength()` / `resumeBytePosition()` assume contiguity. Replace
  with sparse-set semantics: "which part numbers are still missing".
- `resumeBytePosition()` becomes meaningless and should be deleted rather than redefined.
- This is the `data-model.md` change that justifies a new spec.
- Invariant to preserve: checkpoint absence still means **restart**, never **done** (FR-032, FR-033).

### Phase 3 — parallel fetch + upload

- The `for` loop over parts becomes N tasks submitted to the existing delivery executor.
- The executor is already **virtual threads** (`copyDeliveryTaskExecutor`, `SimpleAsyncTaskExecutor`
  with `setVirtualThreads(true)`) — a good fit, since each task blocks on two sockets.
- **Bounded** parallelism — a payload can have up to 10,000 parts (`ChunkPlan.MAX_PARTS`); do not
  submit one task per part.
- Each task opens its own ranged download for exactly its part.

### Phase 4 — failure modes that get harder

1. **Provider ignores Range** (`rangeHonoured == false`): parallel fetch is impossible — skipping
   N bytes on N connections is quadratic waste. Must **fall back to the existing sequential path**.
2. **Credential renewal mid-transfer**: today simple (one stream to reopen). With N streams,
   `credentialNeedsRenewal` has to coordinate reopening across all in-flight parts.
3. **`extendClaimHeartbeat`**: currently advances once per confirmed part. With parallelism the
   claim lease must keep renewing even while the *slowest* part is still in flight, or `ClaimReaper`
   will reap a live worker.
4. **Memory**: parallelism multiplies concurrent socket buffers. Must still never buffer a whole
   part (SC-008).

---

## Related code

| Concern | File |
|---|---|
| Sequential upload loop | `copy/delivery/ChunkedUploader.java` |
| Contiguous-prefix checkpoint | `copy/checkpoint/TransferCheckpoint.java` |
| Range request / 206 handling | `copy/provider/HttpProviderClient.java` |
| Part sizing, 10k part ceiling | `copy/delivery/ChunkPlan.java` |
| Delivery executor (virtual threads) | `copy/config/CopyTaskExecutorConfig.java` |
