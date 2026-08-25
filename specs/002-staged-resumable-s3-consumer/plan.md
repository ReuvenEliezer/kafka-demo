# Implementation Plan: Staged Consumer with Resumable Chunked Object Upload

**Branch**: `002-staged-resumable-s3-consumer` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-staged-resumable-s3-consumer/spec.md`

## Summary

Today the repository has one consumption strategy: `KafkaConsumer` calls `S3EventArchiveService` inline while holding
the message, acknowledging only once S3 succeeds. This feature adds a **second, independent strategy alongside it**,
kept permanently as a comparable example.

The work being consumed is a copy job. A third-party provider signals that a recording is ready for download from its
own servers; the system fetches it and stores it in the company's object store. The staged strategy consumes in
batches, writes each message durably to a PostgreSQL staging table, acknowledges immediately, and lets a separate
worker perform the S3 write later. Payloads at or above a configured threshold (default 100 MB) are uploaded as
sequential multipart chunks, with each confirmed chunk's ordinal **and ETag** recorded in a Redis hash carrying a
sliding expiry — so a retry, even after a full restart, resumes from the first unconfirmed chunk and re-requests the
provider from `confirmedChunks × chunkSize` rather than from zero. Below the threshold, a single streaming `PutObject`
runs with no checkpoint machinery at all. An HTTPS ingress endpoint verifies the provider's signature and publishes one
message per recording file, doing nothing else inline.

Three decisions carry most of the design's weight, and all three are places where the obvious approach fails a stated
criterion:

1. **Memory is held flat by passing the socket through** — provider stream to S3 stream, with SDK retries disabled so
   no part needs to be resettable. Part size *grows with payload size* (FR-026), so anything that buffers a whole part
   fails SC-008 even though it "streams". This is also why `S3TransferManager` cannot be used: it cannot checkpoint
   across a restart. (research.md R3)
2. **Chunk confirmation and TTL refresh are one atomic Lua call.** `HSET` then `EXPIRE` leaves a crash window that
   records a confirmed chunk under the old, shorter TTL — the exact mid-flight expiry FR-038 exists to prevent. (R2)
3. **The two stores have opposite guarantees, deliberately.** PostgreSQL is authoritative for "does this still need
   delivering"; Redis is authoritative for nothing. Checkpoint absence means *restart*, never *done* — the misreading
   the spec singles out as the one that finalizes an empty object. (data-model.md)

## Technical Context

**Language/Version**: Java 21 (retained — CI pins JDK 21; see research.md R21)

**Primary Dependencies**: Spring Boot 3.5.3, Spring Kafka (batch listener + transactional producer), Spring Data JPA,
Spring Data Redis (Lettuce), Flyway, AWS SDK v2 S3 (**bumped 2.28.11 → 2.35.10** for full-object multipart checksums,
research.md R14), Resilience4j 2.2.0, Micrometer/Actuator, Lombok (new code only), `java.net.http.HttpClient`

**Storage**:
- PostgreSQL 17 — `staged_item`, authoritative delivery state (durable, transactional)
- Redis 8 — transfer checkpoints, one hash per destination object (disposable, expiring)
- Amazon S3 / LocalStack — destination object store

**Testing**: JUnit 5, Testcontainers (`KafkaContainer`, `PostgreSQLContainer`, `RedisContainer`,
`LocalStackContainer`), plus a purpose-built `FakeProviderServer` fixture that serves synthetic bytes with genuine
`Range` support and injectable faults (research.md R20)

**Target Platform**: JVM server (single Spring Boot service)

**Project Type**: Single project — Spring Boot service

**Performance Goals**: Intake unaffected by object-store latency (SC-004); an interrupted transfer resumes moving ~10%
of the payload across **both** legs (SC-001); ingress answers within the provider's acknowledgement timeout at peak
notification rate (SC-022)

**Constraints**: Per-transfer memory fixed and independent of payload size (SC-008); chunks sequential, so confirmed
chunks form a contiguous prefix (FR-042); resume position derived, never stored (FR-043); backlog pause must not
trigger consumer-group reassignment (SC-013); release signal only after finalization **and** verification — zero
premature releases (SC-018)

**Scale/Scope**: Payloads from bytes to 5 TiB; S3 limits (5 GiB single request, 10,000 parts, 5 MiB minimum part)
govern chunk-size derivation; ~35 new classes across nine packages, existing five source files effectively untouched

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

No project constitution is defined — `.specify/memory/constitution.md` is the unfilled template shipped by Spec Kit, so
no gates apply. Proceeding on spec requirements alone, as feature 001 did.

**Post-Phase-1 re-check**: no constitution to re-evaluate against. Two things are worth flagging in the spirit of a
complexity gate, since nothing else will catch them:

- **This feature adds two infrastructure dependencies** (PostgreSQL, Redis) to a repository that previously had one
  (Kafka). Both are load-bearing rather than incidental: the spec's clarifications independently fixed Redis as the
  checkpoint store, and FR-007–FR-019 describe a transactional workload with `SKIP LOCKED` claim semantics. Recorded in
  Complexity Tracking below rather than waved through.
- **Adding Lombok leaves the repository with two logging idioms.** Accepted deliberately, with the trade-off stated
  plainly in research.md R21 — the alternative is modifying the baseline strategy, which the spec's scope boundary
  forbids.

## Project Structure

### Documentation (this feature)

```text
specs/002-staged-resumable-s3-consumer/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output — 21 decisions, all NEEDS CLARIFICATION resolved
├── data-model.md        # Phase 1 output — entities, state machines, cross-store invariants
├── quickstart.md        # Phase 1 output — 15 validation scenarios covering all 23 success criteria
├── contracts/           # Phase 1 output
│   ├── notification-ingress.openapi.yaml   # Public HTTPS ingress (FR-069–FR-082)
│   ├── recording-copy-message.md           # Kafka message + headers (FR-075–FR-078)
│   ├── checkpoint-store.md                 # Redis key layout, atomic ops, failure modes
│   ├── provider-client.md                  # Download/credential/release contract
│   └── configuration.md                    # Every knob + the six startup validations
├── checklists/
│   └── requirements.md  # Reviewer-owned spec-quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
src/main/java/com/reuven/kafka/demo/
├── config/                                   # existing package
│   ├── KafkaConsumerConfig.java              # + batchKafkaListenerContainerFactory (FR-006)
│   ├── KafkaProducerConfig.java              # + transactional template for ingress (FR-078)
│   ├── KafkaBackpressureController.java      # + @ConditionalOnProperty(inline) — otherwise unchanged
│   ├── S3Config.java                         # + deliveryS3Client: retries disabled (R3)
│   └── GeneralConfig.java                    # + Clock bean (time is injected, never called statically)
├── controllers/MsgController.java            # unchanged
├── entities/MyEvent.java                     # unchanged
├── services/
│   ├── KafkaConsumer.java                    # + @ConditionalOnProperty(inline) — otherwise unchanged
│   ├── S3EventArchiveService.java            # unchanged — inline baseline keeps s3Upload breaker
│   └── S3ArchiveUnavailableException.java    # unchanged
└── copy/                                     # new feature root
    ├── config/
    │   ├── CopyProperties.java               # @ConfigurationProperties root record; compact ctor runs V1–V6 (R5)
    │   ├── StrategyProfileActivator.java     # EnvironmentPostProcessor: property → profile (R6)
    │   ├── StagingJpaConfig.java
    │   ├── CheckpointRedisConfig.java        # Lua script bean for atomic confirm+TTL
    │   └── ConsumptionStrategy.java          # enum INLINE, STAGED
    ├── ingress/
    │   ├── ProviderNotificationController.java   # verify → publish → respond, nothing else (FR-081)
    │   ├── NotificationSignatureVerifier.java    # HMAC-SHA256, MessageDigest.isEqual (FR-070–FR-072)
    │   ├── NotificationPublisher.java            # transactional fan-out, one message per file (FR-077, FR-078)
    │   ├── NotificationExceptionHandler.java     # @RestControllerAdvice → 400/401/408/503 (FR-080)
    │   └── dto/                                  # ProviderNotification, NotificationFile, UrlValidation*
    ├── message/
    │   ├── RecordingCopyMessage.java
    │   └── CopyMessageHeaders.java
    ├── consumer/
    │   ├── StagedBatchConsumer.java          # batch listener; ack strictly after commit (FR-008, R8)
    │   └── BacklogGovernor.java              # pause/resume with hysteresis (FR-012–FR-014)
    ├── staging/
    │   ├── StagedItem.java                   # @Entity (class + Lombok, not a record — JPA)
    │   ├── DeliveryState.java, ReleaseState.java
    │   ├── StagedItemRepository.java         # SKIP LOCKED claim, backlog counts
    │   └── StagingService.java               # @Transactional batch insert, ON CONFLICT DO NOTHING (FR-009)
    ├── delivery/
    │   ├── DeliveryWorker.java               # claim → transfer → verify → state (R18)
    │   ├── ClaimReaper.java                  # releases expired claims (FR-017)
    │   ├── CopyOrchestrator.java             # size resolution, path selection, retry/backoff
    │   ├── ObjectUploader.java               # strategy interface + enum key
    │   ├── SingleRequestUploader.java        # below threshold: no checkpoint at all (FR-024)
    │   ├── ChunkedUploader.java              # resumable multipart (FR-031–FR-037)
    │   ├── UploaderRegistryConfig.java       # enum-keyed auto-registration, fail-fast on duplicates
    │   ├── ChunkPlan.java                    # chunk-size derivation (R4)
    │   ├── IntegrityVerifier.java            # three-layer verification (R14)
    │   └── ObjectKeyResolver.java            # deterministic, keyed by file id (FR-052)
    ├── checkpoint/
    │   ├── CheckpointStore.java, RedisCheckpointStore.java
    │   ├── TransferCheckpoint.java, ChunkConfirmation.java
    │   └── CheckpointHealthIndicator.java
    ├── provider/
    │   ├── ProviderClient.java, HttpProviderClient.java   # Range resume, 206-vs-200 branch (R11)
    │   ├── ProviderCredential.java, RecordingMetadata.java
    │   ├── ProviderHostAllowlist.java        # per-redirect-hop check (R13)
    │   └── ReleaseSignalService.java         # gated on DELIVERED (FR-064–FR-068)
    ├── cleanup/AbandonedUploadReaper.java    # ListMultipartUploads + abort (FR-055–FR-057)
    ├── observability/CopyMetrics.java        # FR-011, FR-057, FR-058
    └── exception/                            # domain exceptions; no bare RuntimeException

src/main/resources/
├── application.yaml                          # + copy.*, s3Delivery/providerDownload breakers
├── application-copy-inline.yaml              # spring.autoconfigure.exclude JPA + Redis (FR-004)
├── application-copy-staged.yaml              # datasource, redis, batch consumer settings
├── db/migration/V1__staged_item.sql          # table, states, constraints, partial indexes
└── META-INF/spring.factories                 # registers StrategyProfileActivator

src/test/java/com/reuven/kafka/demo/
├── KafkaIntegrationTest.java                 # unchanged
├── S3CircuitBreakerIntegrationTest.java      # unchanged — inline baseline still passes as-is
└── copy/
    ├── support/FakeProviderServer.java       # Range-aware, fault-injectable, streams synthetic bytes (R20)
    ├── support/CopyTestFixtures.java         # per-test data helpers, no shared mutable state
    ├── ResumableUploadIntegrationTest.java   # S1, S2 — SC-001, SC-002, SC-003
    ├── StagedConsumerIntegrationTest.java    # S3, S4 — SC-004, SC-005, SC-006
    ├── IntegrityAndMemoryTest.java           # S5, S6 — SC-007, SC-008, SC-009, SC-010
    ├── StrategyComparisonTest.java           # S7 — SC-011, SC-012
    ├── BacklogGovernorIntegrationTest.java   # S8 — SC-013
    ├── CheckpointLifecycleTest.java          # S9, S10 — SC-016, SC-017
    ├── ReleaseSignalGatingTest.java          # S11 — SC-018
    ├── CredentialRenewalTest.java            # S12 — SC-019, SC-020
    ├── NotificationIngressTest.java          # S13 — SC-021, SC-022, SC-023
    ├── StartupValidationTest.java            # S14 — FR-025, FR-039–FR-041
    ├── AbandonedUploadReaperTest.java        # S15 — SC-014, SC-015
    └── ChunkPlanTest.java                    # unit — chunk-size derivation edge cases

docker-compose.yml                            # + postgres, redis (health-checked, --wait-compatible)
pom.xml                                       # + data-jpa, data-redis, actuator, validation, flyway,
                                              #   postgresql, lombok; testcontainers postgresql + redis;
                                              #   aws-sdk 2.28.11 → 2.35.10
```

**Structure Decision**: Single Spring Boot project, unchanged build layout. The feature's ~35 classes live under a new
`copy/` subtree rather than being spread across the existing flat `config`/`services`/`entities` packages. The existing
packages hold five classes total; adding thirty-five to them would bury the inline baseline that the spec requires to
remain legible as a comparison example. Grouping by capability (`ingress`, `consumer`, `staging`, `delivery`,
`checkpoint`, `provider`, `cleanup`) keeps each package small enough to read, and makes the strategy boundary visible in
the directory tree — which is the point of keeping both strategies side by side.

## Complexity Tracking

> Filled here despite no constitution gate, because these are the choices a reviewer should push back on if any of them
> is wrong.

| Addition | Why needed | Simpler alternative rejected because |
|---|---|---|
| PostgreSQL staging store | FR-007–FR-019 require durable commit-before-ack, atomic batch insert, mutable per-item state, exclusive claims with breakable expiry, and ordered retry scheduling | Reusing Redis for staging too would put the authoritative record in a store the spec explicitly permits to lose data (FR-032). Kafka-as-staging reintroduces per-partition head-of-line blocking, which the spec's own rejected-alternatives section identifies as a poor fit for hour-long transfers |
| Redis checkpoint store | Fixed by the spec's clarification round (FR-027, FR-030); needs per-entry sliding expiry and a cheap write per confirmed chunk | Checkpointing in PostgreSQL would put a write-per-chunk on the transactional store and make disposable state look authoritative — the confusion FR-032/FR-033 exist to prevent |
| `EnvironmentPostProcessor` for strategy selection | FR-004 requires the inactive strategy to consume no resources; that needs `spring.autoconfigure.exclude`, which only varies by profile, while FR-003 wants one configuration knob | `@ConditionalOnProperty` alone fails the context in inline mode (JPA auto-config with no datasource). Exposing the profile directly as the knob works but makes the intent less legible; two knobs that must agree is a configuration trap |
| SDK retries disabled on the delivery `S3Client` | A part streamed live from another socket cannot be `reset()`, and SDK retry requires reset. Failures must fall through to checkpoint-driven resume | Enabling retries needs the part buffered in memory (fails SC-008, since part size scales with payload size) or spooled to disk (an extra full write+read of every byte, plus its own crash-cleanup path) |
| AWS SDK bump 2.28.11 → 2.35.10 | Full-object multipart checksums, which make S3 itself the source of the integrity evidence gating the irreversible release signal (SC-018) | Staying on 2.28.11 leaves only per-part checksums plus a size check — weaker evidence for the one failure the spec calls unrecoverable. Version is already in the local Maven repo, so the build stays offline-capable |
| Lombok, new code only | Standing project conventions (records, `@Slf4j`, `@RequiredArgsConstructor`) | Matching the existing `LogManager` idiom instead would contradict the standing convention across ~35 new classes. Converting the existing five files would violate the spec's "inline strategy is not modified" boundary. Trade-off and its cost stated in research.md R21 |

## Phase Status

| Phase | Output | Status |
|---|---|---|
| 0 — Research | [research.md](./research.md) | Complete — 21 decisions, no `NEEDS CLARIFICATION` remaining |
| 1 — Design & Contracts | [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md) | Complete |
| 2 — Tasks | `tasks.md` | Not started — run `/speckit-tasks` |
