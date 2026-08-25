---

description: "Task list for Staged Consumer with Resumable Chunked Object Upload"
---

# Tasks: Staged Consumer with Resumable Chunked Object Upload

**Input**: Design documents from `/specs/002-staged-resumable-s3-consumer/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: **Included.** The spec's *User Scenarios & Testing* section is mandatory, every user story carries an explicit **Independent Test**, and all 23 Success Criteria are measurable assertions. [quickstart.md](./quickstart.md) already names the 15 scenarios and their test classes. Test tasks precede the implementation they cover.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel — different files, no dependency on incomplete work
- **[Story]**: `[US1]`–`[US7]` map to the seven user stories in spec.md
- **[ING]**: The provider notification ingress (FR-069–FR-082, SC-021–SC-023). The spec puts this **in scope** but assigns it no numbered user story, so it gets its own labelled phase rather than being hidden inside Foundational or Polish. Flagged here so the deviation from `[USn]` is deliberate and visible.

## Path Conventions

Single Spring Boot project: `src/main/java/com/reuven/kafka/demo/`, `src/test/java/com/reuven/kafka/demo/`, `src/main/resources/`, `src/test/resources/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependencies, infrastructure services, and configuration scaffolding.

> **⚠️ Ordering hazard — T002 must not be separated from T001.** The moment `spring-boot-starter-data-jpa` lands on the classpath, Spring Boot's `DataSourceAutoConfiguration` tries to stand up an embedded database and **fails the context** when no `spring.datasource.url` is set. That breaks the two existing tests, which run under the inline strategy and have no datasource. The profile machinery in T002/T003 is what keeps them green. Do not commit T001 alone.

- [X] T001 Add `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime), and `lombok` (provided) to [pom.xml](../../pom.xml)
- [X] T002 Create `StrategyProfileActivator` implementing `EnvironmentPostProcessor` in [copy/config/StrategyProfileActivator.java](../../src/main/java/com/reuven/kafka/demo/copy/config/StrategyProfileActivator.java) — reads `copy.consumer.strategy` and activates profile `copy-inline` or `copy-staged` (research.md R6)
- [X] T003 Register `StrategyProfileActivator` in [src/main/resources/META-INF/spring.factories](../../src/main/resources/META-INF/spring.factories) and create [application-copy-inline.yaml](../../src/main/resources/application-copy-inline.yaml) with `spring.autoconfigure.exclude` for `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration`, `RedisAutoConfiguration`, `FlywayAutoConfiguration` (FR-004)
- [X] T004 Verify the two existing tests still pass with `mvn test -Dtest=KafkaIntegrationTest,S3CircuitBreakerIntegrationTest` — proves the inline baseline is untouched by T001–T003 before any further work lands
- [X] T005 Bump `aws-sdk.version` from `2.28.11` to `2.35.10` in [pom.xml](../../pom.xml) — required for `ChecksumType.FULL_OBJECT` on multipart uploads (research.md R14)
- [X] T006 Add `org.testcontainers:postgresql` and `com.redis:testcontainers-redis` (2.2.4) test dependencies to [pom.xml](../../pom.xml)
- [X] T007 Add `postgres:17-alpine` and `redis:8-alpine` services with health checks to [docker-compose.yml](../../docker-compose.yml) — health checks are required because CI runs `docker compose up -d --wait`
- [X] T008 Create [application-copy-staged.yaml](../../src/main/resources/application-copy-staged.yaml) with datasource, Redis, Flyway, and batch-consumer settings
- [X] T009 Add `copy.*` defaults and the `s3Delivery` / `providerDownload` circuit-breaker instances to [application.yaml](../../src/main/resources/application.yaml) per [contracts/configuration.md](./contracts/configuration.md)
- [X] T010 [P] Add test-profile sizing (5MB threshold, 5MB base part, 60s checkpoint expiry, 3 attempts, 2s max backoff) to [src/test/resources/application.yaml](../../src/test/resources/application.yaml)

**Checkpoint**: Build compiles, existing tests pass, both profiles resolve, infrastructure starts.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The staging store, delivery worker skeleton, provider client, and test fixtures that **every** user story builds on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Configuration and cross-cutting

- [X] T011 [P] Create the domain exception hierarchy in [copy/exception/](../../src/main/java/com/reuven/kafka/demo/copy/exception/) — `CopyException` base plus `InvalidChunkingThresholdException`, `InvalidCheckpointExpiryException`, `ProviderUnavailableException`, `RecordingNotFoundException`, `DisallowedProviderHostException`, `S3DeliveryUnavailableException`, `CheckpointUnavailableException`, `IntegrityVerificationException`, `DuplicateUploaderRegistrationException`
- [X] T012 Create `CopyProperties` `@ConfigurationProperties("copy")` root record with nested records in [copy/config/CopyProperties.java](../../src/main/java/com/reuven/kafka/demo/copy/config/CopyProperties.java) — compact constructor enforces validations **V1–V6** from [contracts/configuration.md](./contracts/configuration.md#startup-validation-summary), each failure naming both operands and the relation (FR-025, FR-039, FR-040, FR-041)
- [X] T013 [P] Add a `Clock` bean to [config/GeneralConfig.java](../../src/main/java/com/reuven/kafka/demo/config/GeneralConfig.java) — all time in business logic is injected, never `Instant.now()`
- [X] T014 [P] Create `ConsumptionStrategy` enum (`INLINE`, `STAGED`) in [copy/config/ConsumptionStrategy.java](../../src/main/java/com/reuven/kafka/demo/copy/config/ConsumptionStrategy.java)
- [X] T015 Write `StartupValidationTest` in [copy/StartupValidationTest.java](../../src/test/java/com/reuven/kafka/demo/copy/StartupValidationTest.java) — six cases, each asserting context refresh fails **and** that the message names the offending keys (quickstart S14)

### Staging store

- [X] T016 Create Flyway migration [db/migration/V1__staged_item.sql](../../src/main/resources/db/migration/V1__staged_item.sql) — table, columns, both unique constraints, and the four partial indexes from [data-model.md](./data-model.md#11-staged_item)
- [X] T017 [P] Create `DeliveryState` and `ReleaseState` enums in [copy/staging/](../../src/main/java/com/reuven/kafka/demo/copy/staging/) — distinguishing at minimum awaiting-delivery, in-progress, delivered, and permanently-failed (FR-010) — per the state machines in [data-model.md](./data-model.md#12-delivery_state--states-and-transitions)
- [X] T018 Create `StagedItem` `@Entity` in [copy/staging/StagedItem.java](../../src/main/java/com/reuven/kafka/demo/copy/staging/StagedItem.java) — a Lombok-annotated class, **not** a record (JPA needs mutability, a no-arg constructor, and proxy compatibility). Holds the provider's **stable** recording identifier and never a captured download credential, which would be expired by the time a long retry span elapsed (FR-059)
- [X] T019 Create `StagedItemRepository` in [copy/staging/StagedItemRepository.java](../../src/main/java/com/reuven/kafka/demo/copy/staging/StagedItemRepository.java) — `SELECT ... FOR UPDATE SKIP LOCKED` claim query, backlog count, oldest-undelivered age, stale-claim scan, delivered-but-unreleased scan
- [X] T020 Create `StagingService` in [copy/staging/StagingService.java](../../src/main/java/com/reuven/kafka/demo/copy/staging/StagingService.java) — `@Transactional` batch insert with `ON CONFLICT (recording_file_id) DO NOTHING`, all-or-nothing per batch (FR-009)

### Delivery skeleton

- [X] T021 [P] Create `ObjectKeyResolver` in [copy/delivery/ObjectKeyResolver.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ObjectKeyResolver.java) — `{prefix}/{accountId}/{sessionId}/{recordingFileId}`, sanitised and length-bounded, keyed by **file** id (FR-052)
- [X] T022 Add a `deliveryS3Client` bean with `AwsRetryStrategy.doNotRetry()` to [config/S3Config.java](../../src/main/java/com/reuven/kafka/demo/config/S3Config.java) — SDK retry cannot `reset()` a stream pulled live from another socket; failures must fall through to checkpoint-driven resume (research.md R3). Write credentials come from the AWS credential chain only — **never** from message content or any provider-supplied field, and kept separate from the provider read credentials (FR-063)
- [X] T023 [P] Create `ObjectUploader` interface, `UploadPath` enum (`SINGLE_REQUEST`, `CHUNKED`), and `UploaderRegistryConfig` building an `EnumMap` in [copy/delivery/](../../src/main/java/com/reuven/kafka/demo/copy/delivery/) — fails at startup on a duplicate key with `DuplicateUploaderRegistrationException`
- [X] T024 Create `UploadPathSelector` in [copy/delivery/UploadPathSelector.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/UploadPathSelector.java) — routes on `copy.chunking.threshold`; one threshold governs both chunking and checkpointing (FR-023, FR-024)
- [X] T025 Create `DeliveryWorker` in [copy/delivery/DeliveryWorker.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/DeliveryWorker.java) — claim in a **short** transaction, run the transfer **outside** any transaction, exclusive claim before any attempt (FR-016), exponential backoff up to `max-attempts` (FR-018), terminal-failure transition (research.md R18, FR-015–FR-019)
- [X] T026 [P] Create `ClaimReaper` in [copy/delivery/ClaimReaper.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ClaimReaper.java) — `@Scheduled` release of claims whose `claim_expires_at` has passed (FR-017)

### Provider client

- [X] T027 [P] Create `ProviderCredential` and `RecordingMetadata` records in [copy/provider/](../../src/main/java/com/reuven/kafka/demo/copy/provider/) — never persisted, never logged
- [X] T028 [P] Create `ProviderHostAllowlist` in [copy/provider/ProviderHostAllowlist.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/ProviderHostAllowlist.java) — https-only, exact host or registrable-domain suffix (FR-062)
- [X] T029 Create `ProviderClient` interface in [copy/provider/ProviderClient.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/ProviderClient.java) per [contracts/provider-client.md](./contracts/provider-client.md)
- [X] T030 Implement `HttpProviderClient` in [copy/provider/HttpProviderClient.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/HttpProviderClient.java) — `java.net.http.HttpClient` with `followRedirects(NEVER)` and a **per-hop** allowlist check; `mintDownloadCredential`, `fetchMetadata`, `openDownload` with `Range`, and status-code classification into transient vs permanent exceptions (FR-046, FR-060, FR-062)

### Test fixtures

- [X] T031 [P] Create `FakeProviderServer` in [copy/support/FakeProviderServer.java](../../src/test/java/com/reuven/kafka/demo/copy/support/FakeProviderServer.java) — `com.sun.net.httpserver.HttpServer` streaming deterministic synthetic bytes from a seeded pattern (never materialised), with genuine `Range` support and switchable faults: ignore-range, fail-after-N-bytes, expire-credential-after-N-bytes, throttle, delete-recording. Records bytes served and release signals received (research.md R20). Plain HTTP per R20 — `ProviderHostAllowlist` exempts loopback from https-only so this works without weakening production posture
- [X] T032 [P] Create `CopyIntegrationTestBase` in [copy/support/CopyIntegrationTestBase.java](../../src/test/java/com/reuven/kafka/demo/copy/support/CopyIntegrationTestBase.java) — static `KafkaContainer`, `PostgreSQLContainer`, `RedisContainer`, `LocalStackContainer` plus `@DynamicPropertySource` wiring for container-derived values, and `@TestPropertySource` for static ones (`copy.consumer.strategy` must be visible to `StrategyProfileActivator` during environment preparation — `@DynamicPropertySource` values are injected too late). **Helper methods only, no shared mutable fixture state**
- [X] T033 [P] Create `CopyTestFixtures` in [copy/support/CopyTestFixtures.java](../../src/test/java/com/reuven/kafka/demo/copy/support/CopyTestFixtures.java) — per-test builders for staged items and copy messages

### Observability scaffold

- [X] T034 Create `CopyMetrics` in [copy/observability/CopyMetrics.java](../../src/main/java/com/reuven/kafka/demo/copy/observability/CopyMetrics.java) — Micrometer counters and timer registration; gauges are bound by their owning stories
- [X] T107 [P] Write `ConcurrentClaimTest` in [copy/ConcurrentClaimTest.java](../../src/test/java/com/reuven/kafka/demo/copy/ConcurrentClaimTest.java) — N workers racing for one staged item, asserting **exactly one** acquires it (FR-016); a worker heartbeating normally is **never** reclaimed beneath itself. Covers data-model.md invariant I5 and the "two workers pick up the same staged item" edge case. (The reaper-reclaims-then-a-second-worker-resumes-from-checkpoint half of this task needs the checkpoint store wired into a real transfer, which arrives with US1 — T045-T049; revisit then if a dedicated case is still warranted beyond what `CheckpointLifecycleTest`/`ResumableUploadIntegrationTest` already cover.) Verified green against real Testcontainers Postgres/Redis/Kafka/LocalStack

> **ID ordering note**: T107 is appended rather than inserted. Renumbering ~80 downstream tasks would invalidate the cross-references in T051/T067 and the dependency table for no functional gain. It executes with Phase 2.

**Checkpoint**: A staged item can be inserted, claimed, attempted, retried, and failed terminally. No uploader implementation exists yet — that is deliberate; each path arrives with its story.

---

## Phase 3: User Story 1 - A very large upload survives an interruption without starting over (Priority: P1) 🎯 MVP

**Goal**: An interrupted chunked transfer resumes from the first unconfirmed chunk on both legs — re-downloading and re-uploading only what was never confirmed — including across a full service restart.

**Independent Test**: Stage an item whose payload spans ~10 chunks; interrupt after ~9 are confirmed; restart the Spring context; restore connectivity. Confirm only ~10% moves across each leg and the finalized object is byte-identical to the source.

### Tests for User Story 1 ⚠️

> Write these first and confirm they fail before implementing.

- [X] T035 [P] [US1] Write `ChunkPlanTest` in [copy/ChunkPlanTest.java](../../src/test/java/com/reuven/kafka/demo/copy/ChunkPlanTest.java) — unit cases at each boundary: just above threshold, part-count ceiling at 5 TiB (expect ~537 MB parts), minimum-part-size floor, final part permitted below minimum, and rejection above the 5 GiB part maximum
- [X] T036 [P] [US1] Write `ResumableUploadIntegrationTest` in [copy/ResumableUploadIntegrationTest.java](../../src/test/java/com/reuven/kafka/demo/copy/ResumableUploadIntegrationTest.java) — quickstart S1 and S2. Asserts on **measured bytes on both legs** (`FakeProviderServer` byte counter and LocalStack `UploadPart` count), not merely on success; includes the full-context-restart case (SC-001, SC-002, SC-003)
- [X] T037 [P] [US1] Write `CheckpointLifecycleTest` in [copy/CheckpointLifecycleTest.java](../../src/test/java/com/reuven/kafka/demo/copy/CheckpointLifecycleTest.java) — quickstart S9 and S10: TTL slides with progress under a 10s expiry and a 60s transfer; `FLUSHALL` mid-transfer restarts and still completes; and the critical negative — **no item reaches `DELIVERED` on the strength of a missing checkpoint** (SC-016, SC-017, invariant I2)
- [X] T038 [P] [US1] Write `CredentialRenewalTest` in [copy/CredentialRenewalTest.java](../../src/test/java/com/reuven/kafka/demo/copy/CredentialRenewalTest.java) — quickstart S12: credential expiring at ~30% completes without restarting; a retry after the original notification credential would have expired still succeeds (SC-019, SC-020)

### Implementation for User Story 1

- [X] T039 [P] [US1] Create `TransferCheckpoint` and `ChunkConfirmation` records in [copy/checkpoint/](../../src/main/java/com/reuven/kafka/demo/copy/checkpoint/) — `ChunkConfirmation` carries ordinal **and** ETag; an ordinal alone cannot finalize (FR-028)
- [X] T040 [P] [US1] Create `CheckpointStore` interface in [copy/checkpoint/CheckpointStore.java](../../src/main/java/com/reuven/kafka/demo/copy/checkpoint/CheckpointStore.java) per [contracts/checkpoint-store.md](./contracts/checkpoint-store.md)
- [X] T041 [US1] Create the atomic confirm Lua script and `RedisScript` bean in [copy/config/CheckpointRedisConfig.java](../../src/main/java/com/reuven/kafka/demo/copy/config/CheckpointRedisConfig.java) — `EXISTS` guard, `HSET`, `EXPIRE` in one `EVALSHA`, refreshing the sliding expiry on **every** confirmation (FR-038). A separate `HSET` + `EXPIRE` records a confirmation under the **old, shorter TTL** if it crashes between them (research.md R2)
- [X] T042 [US1] Implement `RedisCheckpointStore` in [copy/checkpoint/RedisCheckpointStore.java](../../src/main/java/com/reuven/kafka/demo/copy/checkpoint/RedisCheckpointStore.java) — `create`, `confirm` (script, returning `false` when the entry vanished), `read`, `delete`; key `xfer:{bucket}:{key}`. Entries live in Redis, not worker memory, so they survive a full service restart (FR-027, FR-029, FR-030, FR-036)
- [X] T043 [US1] Implement contiguous-prefix resume derivation in [RedisCheckpointStore.java](../../src/main/java/com/reuven/kafka/demo/copy/checkpoint/RedisCheckpointStore.java) — largest `k` where `part:1…part:k` all exist; `resumeBytePosition = k × chunkSize`. Compute the **prefix**, not the field count: a gap must cost a re-transfer, never a corrupt object (FR-042, FR-043). (Lives as `TransferCheckpoint`'s own derivation methods, returned by `RedisCheckpointStore.read()`, rather than duplicated inline)
- [X] T044 [P] [US1] Implement `ChunkPlan` in [copy/delivery/ChunkPlan.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkPlan.java) — `clamp(max(basePartSize, ceilToMiB(size / 10000)), 5MiB, 5GiB)` (research.md R4, FR-026)
- [X] T045 [US1] Implement `ChunkedUploader` in [copy/delivery/ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java) — `CreateMultipartUpload` → write checkpoint → sequential `UploadPart` via `RequestBody.fromContentProvider(stream, partSize, contentType)` reading straight from the provider socket → confirm each part → `CompleteMultipartUpload`. **Never buffer a whole part**: part size scales with payload size, so buffering fails SC-008 Request the payload from the derived resume position (FR-044) and treat any chunk whose acceptance is unknown as unconfirmed, re-sending it harmlessly (FR-037) (FR-021, FR-022, FR-031, FR-035)
- [X] T046 [US1] Add stale-upload validation to [ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java) — `ListParts` before resuming; on `NoSuchUpload`, abort, delete the checkpoint, and restart from part 1 (FR-034)
- [X] T047 [US1] Add the range-ignored branch to [ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java) — on `200` rather than `206`, read and discard the first `resumeBytePosition` bytes so the upload leg still resumes. This is the whole of SC-002 and is invisible until a real provider misbehaves (FR-045)
- [X] T048 [US1] Add credential renewal at chunk boundaries to [ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java) — re-mint when remaining lifetime drops below `credential-renewal-margin` and reconnect at the derived resume position. **Boundaries only**: mid-chunk, the derived position is not yet correct (FR-061, research.md R12)
- [X] T049 [US1] Extend the claim heartbeat in [ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java) and [StagedItemRepository.java](../../src/main/java/com/reuven/kafka/demo/copy/staging/StagedItemRepository.java) — every chunk confirmation also extends `claim_expires_at`, so progress on a multi-hour transfer keeps the claim alive (FR-017)
- [X] T050 [P] [US1] Implement `IntegrityVerifier` in [copy/delivery/IntegrityVerifier.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/IntegrityVerifier.java) — per-part CRC32C, full-object CRC32C from `CompleteMultipartUpload` compared against the streamed accumulation, and `HeadObject` content length (research.md R14, FR-054). **Deviation, confirmed empirically against real LocalStack**: AWS SDK 2.35's flexible checksums (`ChecksumType.FULL_OBJECT` and per-part `ChecksumAlgorithm`) proved unworkable in every combination tried — see the memory entry `localstack-s3-checksums` and `ChunkedUploader`'s class javadoc. Finalization uses `CompletedPart.eTag` only; `IntegrityVerifier` gates on `HeadObject` size alone (layer 3), logging a full-object checksum as corroborating evidence only when S3 happens to return one. Byte-identity (SC-007) is proven instead by integration tests reading the finalized object back against the known source bytes.
- [X] T051 [US1] Gate `DELIVERY_IN_PROGRESS → DELIVERED` on all three verification layers in [DeliveryWorker.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/DeliveryWorker.java), and fail without finalizing when the stream ends short of the declared size (FR-051, FR-053)
- [X] T052 [US1] Delete the checkpoint entry on finalization in [ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java), and treat a missing entry as **restart** everywhere it is read — never as completion (FR-032, FR-033, FR-036)
- [X] T053 [P] [US1] Register `ChunkedUploader` under `UploadPath.CHUNKED` in [UploaderRegistryConfig.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/UploaderRegistryConfig.java) and add the `s3Delivery` `@CircuitBreaker` to the delivery worker's S3 calls (FR-020)

**Checkpoint**: US1 is independently functional. Large payloads resume correctly across interruptions and restarts. Below-threshold payloads have no uploader yet — by design.

---

## Phase 4: User Story 2 - Intake keeps running while the object store is degraded (Priority: P1)

**Goal**: Batched consumption stages messages durably and acknowledges immediately, so intake rate is independent of object-store health, with backlog pressure the only thing that stops it.

**Independent Test**: Stop LocalStack, publish a steady stream, and confirm consumption and acknowledgement continue at full rate with no consumer-group lag growth. Restart it and confirm the backlog drains unattended.

### Tests for User Story 2 ⚠️

- [X] T054 [P] [US2] Write `StagedConsumerIntegrationTest` in [copy/StagedConsumerIntegrationTest.java](../../src/test/java/com/reuven/kafka/demo/copy/StagedConsumerIntegrationTest.java) — quickstart S3 and S4: intake during a total S3 outage, unattended drain on recovery, and crash injection at all four stage boundaries proving no acknowledged message is absent from **both** stores (SC-004, SC-005, SC-006)
- [X] T055 [P] [US2] Write `BacklogGovernorIntegrationTest` in [copy/BacklogGovernorIntegrationTest.java](../../src/test/java/com/reuven/kafka/demo/copy/BacklogGovernorIntegrationTest.java) — quickstart S8. **Asserts the consumer-group generation id is unchanged** across the pause, not merely that the container reports paused; a rebalance here would otherwise regress silently. Plus the mid-batch boundary case: cross the high-water mark partway through a batch and assert the in-flight batch is staged and acknowledged **in full**, never half, with the pause taking effect on the next poll (SC-013, FR-009, FR-012)

### Implementation for User Story 2

- [X] T056 [P] [US2] Create `RecordingCopyMessage` record and `CopyMessageHeaders` constants in [copy/message/](../../src/main/java/com/reuven/kafka/demo/copy/message/) per [contracts/recording-copy-message.md](./contracts/recording-copy-message.md) — built ahead of schedule during Foundational so `CopyTestFixtures` (T033) could reference it
- [X] T057 [US2] Add `batchKafkaListenerContainerFactory` to [config/KafkaConsumerConfig.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaConsumerConfig.java) — `setBatchListener(true)`, `AckMode.MANUAL_IMMEDIATE`, `max.poll.records`, `fetch.max.wait.ms`, `isolation.level=read_committed` (FR-006)
- [X] T058 [US2] Implement `StagedBatchConsumer` in [copy/consumer/StagedBatchConsumer.java](../../src/main/java/com/reuven/kafka/demo/copy/consumer/StagedBatchConsumer.java) — `@ConditionalOnProperty(havingValue="staged")`. **Acknowledge strictly after the staging transaction commits, never inside it**: acking inside the transactional method lets the offset commit survive a rollback and lose the message (FR-001, FR-007, FR-008, research.md R8)
- [X] T059 [US2] Resolve destination key and size at staging time in [StagedBatchConsumer.java](../../src/main/java/com/reuven/kafka/demo/copy/consumer/StagedBatchConsumer.java) and delegate to `StagingService` for the atomic batch insert (FR-009)
- [X] T060 [US2] Implement `BacklogGovernor` in [copy/consumer/BacklogGovernor.java](../../src/main/java/com/reuven/kafka/demo/copy/consumer/BacklogGovernor.java) — `@Scheduled` check, `container.pause()` at the high-water mark and `resume()` below the low-water mark. `pause()` keeps `poll()` running so the consumer stays in its group (FR-012, FR-013)
- [X] T061 [US2] Log a distinctly identifiable status change on every pause/resume transition in [BacklogGovernor.java](../../src/main/java/com/reuven/kafka/demo/copy/consumer/BacklogGovernor.java), including reason and backlog size at that moment (FR-014)
- [X] T062 [P] [US2] Bind the `copy.backlog.size` and `copy.backlog.oldest.age` gauges in [CopyMetrics.java](../../src/main/java/com/reuven/kafka/demo/copy/observability/CopyMetrics.java) — age is what distinguishes a healthy steady backlog from a stalled one (FR-011, FR-058)
- [X] T063 [P] [US2] Add delivery throughput, retry, and permanent-failure counters to [CopyMetrics.java](../../src/main/java/com/reuven/kafka/demo/copy/observability/CopyMetrics.java) (FR-058)

**Checkpoint**: US1 and US2 both work independently. Intake is decoupled from delivery.

---

## Phase 5: User Story 3 - The source is released only once our copy is safe (Priority: P1)

**Goal**: The provider is told it may discard its copy only after ours is finalized **and** verified — the one irreversible action in the feature.

**Independent Test**: Inject failures mid-chunk, before finalization, and during finalization; confirm zero release signals reach the provider in every case. Then let a copy complete and confirm exactly one signal is sent.

### Tests for User Story 3 ⚠️

- [X] T064 [P] [US3] Write `ReleaseSignalGatingTest` in [copy/ReleaseSignalGatingTest.java](../../src/test/java/com/reuven/kafka/demo/copy/ReleaseSignalGatingTest.java) — quickstart S11. `FakeProviderServer` must record a **hard zero** premature releases across all three injection points; plus exactly-once on success, harmless retry, and no revert of delivered state (SC-018)

### Implementation for User Story 3

- [X] T065 [US3] Add `signalRelease` to [ProviderClient.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/ProviderClient.java) and [HttpProviderClient.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/HttpProviderClient.java) returning `ReleaseOutcome` — `ALREADY_RELEASED` is a distinct **success**, because a crash between signalling and recording causes a re-send and the provider having already released is the state we wanted (FR-066)
- [X] T066 [US3] Implement `ReleaseSignalService` in [copy/provider/ReleaseSignalService.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/ReleaseSignalService.java) — `@Scheduled` scan of `release_state = PENDING`, signal, record outcome, back off on failure (FR-064, FR-067)
- [X] T067 [US3] Enforce the release precondition structurally in [ReleaseSignalService.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/ReleaseSignalService.java) and [DeliveryWorker.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/DeliveryWorker.java) — `release_state` leaves `NOT_APPLICABLE` only when `delivery_state` becomes `DELIVERED`, which T051 already gates on verification. Being marked delivered is not sufficient on its own (FR-065, invariant I6)
- [X] T068 [US3] Ensure a release failure never reverts `delivery_state` and never re-copies the payload, in [ReleaseSignalService.java](../../src/main/java/com/reuven/kafka/demo/copy/provider/ReleaseSignalService.java) — the two state machines are separate so this is structural rather than a rule to remember (FR-068, invariant I7)
- [X] T069 [P] [US3] Add the tagged `copy.release.outcome` counter in [CopyMetrics.java](../../src/main/java/com/reuven/kafka/demo/copy/observability/CopyMetrics.java) and surface delivered-but-unreleased items as a distinct operational condition (FR-067)

**Checkpoint**: All three P1 stories complete. This is the recommended MVP stopping point.

---

## Phase 6: Provider Notification Ingress (FR-069–FR-082)

**Goal**: A public HTTPS endpoint that verifies the provider's signature and publishes one message per recording file — and does nothing else inline.

**Independent Test**: Valid, invalid, stale, and replayed notifications; the registration challenge; a broker outage. Confirm nothing is published on any verification failure.

### Tests for Ingress ⚠️

- [X] T070 [P] [ING] Write `NotificationIngressTest` in [copy/NotificationIngressTest.java](../../src/test/java/com/reuven/kafka/demo/copy/NotificationIngressTest.java) — quickstart S13, all seven cases including latency at peak rate (SC-021, SC-022, SC-023)

### Implementation for Ingress

- [X] T071 [P] [ING] Create ingress DTO records in [copy/ingress/dto/](../../src/main/java/com/reuven/kafka/demo/copy/ingress/dto/) — `ProviderNotification`, `NotificationFile`, `UrlValidationRequest`, `UrlValidationResponse`, `AcceptedResponse`, `ErrorResponse` per [contracts/notification-ingress.openapi.yaml](./contracts/notification-ingress.openapi.yaml)
- [X] T072 [ING] Add a raw-body caching filter for the notification path in [copy/ingress/RawBodyCachingFilter.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/RawBodyCachingFilter.java), bounded by `max-body-size` — the signature covers the **raw bytes**, and verifying a Jackson re-serialisation breaks on any formatting difference
- [X] T073 [ING] Implement `NotificationSignatureVerifier` in [copy/ingress/NotificationSignatureVerifier.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/NotificationSignatureVerifier.java) — HMAC-SHA256 over `v0:{timestamp}:{rawBody}`, compared with `MessageDigest.isEqual`. `String.equals` leaks, via timing, how many leading bytes matched (FR-070, FR-071)
- [X] T074 [ING] Add the signed-timestamp freshness range check in [NotificationSignatureVerifier.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/NotificationSignatureVerifier.java) against the injected `Clock` — signing proves the timestamp was not tampered with, but a **replayed capture carries a genuine signature over a genuine timestamp**; only the range check stops it (FR-072)
- [X] T075 [ING] Add a transactional producer factory and `KafkaTemplate` (`transactional.id`, `acks=all`, `enable.idempotence=true`) to [config/KafkaProducerConfig.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaProducerConfig.java) as a **separate** bean, leaving the existing non-transactional producer untouched (FR-002)
- [X] T076 [ING] Implement `NotificationPublisher` in [copy/ingress/NotificationPublisher.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/NotificationPublisher.java) — `executeInTransaction` fan-out, one message per recording file, all-or-none, with `x-recording-size` and `x-provider-event-id` headers attached (FR-075, FR-076, FR-077, FR-078)
- [X] T077 [ING] Implement `ProviderNotificationController` in [copy/ingress/ProviderNotificationController.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/ProviderNotificationController.java) — verify, publish, respond; response written only after the transaction commits. **No copying, no staging writes, no provider callbacks inline** (FR-074, FR-079, FR-081)
- [X] T078 [ING] Add the `endpoint.url_validation` challenge branch in [ProviderNotificationController.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/ProviderNotificationController.java) returning `plainToken` plus its HMAC (FR-073)
- [X] T079 [P] [ING] Implement `NotificationExceptionHandler` `@RestControllerAdvice` in [copy/ingress/NotificationExceptionHandler.java](../../src/main/java/com/reuven/kafka/demo/copy/ingress/NotificationExceptionHandler.java) — 400/401/408 permanent vs 503 transient, so the provider stops retrying the former and continues retrying the latter. Never echo the signature, secret, or download URLs (FR-080)
- [X] T080 [P] [ING] Bind `copy.notification.secret` from the `COPY_NOTIFICATION_SECRET` environment variable only, never from a config file, and document it in [README.md](../../README.md) (FR-082)

**Checkpoint**: The system can receive real provider notifications end to end.

---

## Phase 7: User Story 4 - Both consumption strategies coexist as comparable examples (Priority: P2)

**Goal**: Either strategy runs, selected by one configuration value, with the inactive one fully inert.

**Independent Test**: Switch strategies with no code change, run each in turn under an identical object-store outage and recovery, and confirm their distinct acknowledgement and backlog behaviour is directly observable while each still delivers everything it acknowledged.

### Tests for User Story 4 ⚠️

- [X] T081 [P] [US4] Write `StrategyComparisonTest` in [copy/StrategyComparisonTest.java](../../src/test/java/com/reuven/kafka/demo/copy/StrategyComparisonTest.java) — quickstart S7: run each strategy in turn under an identical induced S3 outage and recovery, asserting the **contrast** (inline pauses and accrues topic lag; staged keeps consuming and accrues staged backlog) and that each delivers everything it acknowledged. Assert clean startup under both with no error attributable to the inactive one. **Do not assert object equality** — the two strategies carry different work (FR-005, SC-011, SC-012)

### Implementation for User Story 4

- [X] T082 [US4] Add `@ConditionalOnProperty(name="copy.consumer.strategy", havingValue="inline", matchIfMissing=true)` to [services/KafkaConsumer.java](../../src/main/java/com/reuven/kafka/demo/services/KafkaConsumer.java) — **this annotation and nothing else**; behaviour when active must stay byte-for-byte identical (FR-002, FR-003)
- [X] T083 [US4] Add the same conditional to [config/KafkaBackpressureController.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaBackpressureController.java) — under the staged strategy nothing may pause the consumer on S3 health, or SC-004 is defeated (FR-020, research.md R7)
- [X] T084 [US4] Audit every staged-strategy component under [src/main/java/com/reuven/kafka/demo/copy/](../../src/main/java/com/reuven/kafka/demo/copy/) for its `@ConditionalOnProperty` guard and confirm a clean inline start creates no JPA, Redis, or worker beans (FR-004)
- [X] T085 [P] [US4] Document running each strategy in [README.md](../../README.md), including the behavioural contrast under an object-store outage — that contrast is the feature's justification

**Checkpoint**: Both strategies coexist as runnable, comparable examples.

---

## Phase 8: User Story 5 - Small payloads are not penalized by chunking machinery (Priority: P2)

**Goal**: Below-threshold payloads take a single streaming transfer with no chunk bookkeeping created at all.

**Independent Test**: Deliver one payload below the threshold and one above; confirm the small one creates no chunk-tracking records and the large one uses the chunked path.

**Dependency note**: scenario 1 is fully independent; scenario 2 ("at or above the threshold uses chunks") asserts against US1's path, so it is meaningful only once Phase 3 is complete.

### Tests for User Story 5 ⚠️

- [X] T086 [P] [US5] Write `IntegrityAndMemoryTest` in [copy/IntegrityAndMemoryTest.java](../../src/test/java/com/reuven/kafka/demo/copy/IntegrityAndMemoryTest.java) — quickstart S5 and S6: byte-identity across 1 KiB / 6 MiB / 60 MiB, **heap sampled during the 60 MiB transfer must not scale with payload size**, and zero Redis keys plus zero `CreateMultipartUpload` for the small payload (SC-007, SC-008, SC-009)

### Implementation for User Story 5

- [X] T087 [US5] Implement `SingleRequestUploader` in [copy/delivery/SingleRequestUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/SingleRequestUploader.java) — one streaming `PutObject` with known content length, registered under `UploadPath.SINGLE_REQUEST` (FR-023)
- [X] T088 [US5] Assert structurally in [UploadPathSelector.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/UploadPathSelector.java) that no checkpoint entry is created below the threshold — one threshold governs both, so there is no band where a payload pays for chunking without gaining resumability (FR-024, invariant I4)
- [X] T089 [P] [US5] Confirm [UploadPathSelector.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/UploadPathSelector.java) reads the threshold per delivery rather than caching it at startup, so a restart with a new value governs subsequent decisions (US5 scenario 3)

**Checkpoint**: The common case is fast and leaves no state behind.

---

## Phase 9: User Story 6 - Payload size is known without paying for an extra round-trip (Priority: P3)

**Goal**: Size comes from message metadata when present; a lookup is the fallback, paid at most once per item; a wrong declared size still yields a correct object.

**Independent Test**: Publish messages with a declared size, without one, and with one that understates the payload. Confirm no probe in the first case and a complete correct object in all three.

### Tests for User Story 6 ⚠️

- [X] T090 [P] [US6] Write `SizeResolutionTest` in [copy/SizeResolutionTest.java](../../src/test/java/com/reuven/kafka/demo/copy/SizeResolutionTest.java) — zero provider metadata calls when the header is present and plausible; exactly one lookup when absent, not repeated across retries; implausible values rejected; an understated size still completing (SC-010)

### Implementation for User Story 6

- [X] T091 [US6] Implement size resolution in [copy/delivery/SizeResolver.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/SizeResolver.java) — read `x-recording-size` and use it without any extra call (FR-047)
- [X] T092 [US6] Add plausibility validation to [SizeResolver.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/SizeResolver.java) — absent, non-numeric, negative, or above `max-plausible-bytes` falls back to a metadata lookup rather than being used as-is (FR-048)
- [X] T093 [US6] Persist a looked-up size to `resolved_size_bytes` via [StagedItemRepository.java](../../src/main/java/com/reuven/kafka/demo/copy/staging/StagedItemRepository.java) so the lookup is paid at most once per item across all retries, including retries days later (FR-049)
- [X] T094 [US6] Handle an understated declared size in [DeliveryWorker.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/DeliveryWorker.java) — switch to the chunked path mid-stream when the payload exceeds what a single request can carry, so the transfer still completes (FR-050)
- [X] T095 [US6] Fail the transfer without finalizing in [ChunkedUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/ChunkedUploader.java) and [SingleRequestUploader.java](../../src/main/java/com/reuven/kafka/demo/copy/delivery/SingleRequestUploader.java) when the stream ends before the declared size is reached — a truncated object must never be completed, which also makes a release signal for it unreachable (FR-051)

**Checkpoint**: The size decision costs nothing in the common case and is robust when the hint is wrong.

---

## Phase 10: User Story 7 - Abandoned partial transfers do not accumulate silently (Priority: P3)

**Goal**: Partial upload state from permanently abandoned transfers is reclaimed on a predictable schedule and reported while it exists.

**Independent Test**: Start a chunked transfer, abandon it permanently, and confirm its partial state is reclaimed and reported after the retention window.

### Tests for User Story 7 ⚠️

- [X] T096 [P] [US7] Write `AbandonedUploadReaperTest` in [copy/AbandonedUploadReaperTest.java](../../src/test/java/com/reuven/kafka/demo/copy/AbandonedUploadReaperTest.java) — quickstart S15: past-retention uploads aborted, **still-retryable uploads preserved**, a permanently failed item not delaying unrelated work (SC-014, SC-015)

### Implementation for User Story 7

- [X] T097 [US7] Implement `AbandonedUploadReaper` in [copy/cleanup/AbandonedUploadReaper.java](../../src/main/java/com/reuven/kafka/demo/copy/cleanup/AbandonedUploadReaper.java) — `@Scheduled` `ListMultipartUploads` and abort past the retention window. S3 never expires these on its own and bills for them (FR-055)
- [X] T098 [US7] Rely in [AbandonedUploadReaper.java](../../src/main/java/com/reuven/kafka/demo/copy/cleanup/AbandonedUploadReaper.java) on the startup-validated window ordering so a retryable transfer can never be reaped — the retention window is strictly longer than the maximum retry span, making FR-056 true by construction rather than by a runtime check
- [X] T099 [P] [US7] Bind `copy.transfers.unfinished` and `copy.transfers.unfinished.bytes` gauges in [CopyMetrics.java](../../src/main/java/com/reuven/kafka/demo/copy/observability/CopyMetrics.java), including uploads whose checkpoint has expired and which are therefore no longer resumable (FR-057)

**Checkpoint**: All seven user stories and the ingress are complete.

---

## Phase 11: Polish & Cross-Cutting Concerns

- [X] T100 [P] Add `CheckpointHealthIndicator` in [copy/checkpoint/CheckpointHealthIndicator.java](../../src/main/java/com/reuven/kafka/demo/copy/checkpoint/CheckpointHealthIndicator.java) and the `copy.checkpoint.errors` counter — sustained Redis unavailability costs only bytes, but it silently turns every large transfer non-resumable, so it must be visible (FR-058)
- [X] T101 [P] Verify across [src/main/java/com/reuven/kafka/demo/](../../src/main/java/com/reuven/kafka/demo/) that no credential, secret, or signature appears in any log line at any level, and that no `System.out`/`err` exists in production paths
- [X] T102 [P] Confirm across [src/main/java/com/reuven/kafka/demo/](../../src/main/java/com/reuven/kafka/demo/) that every `@RestController` returns records and never entities, and that no bare `RuntimeException`/`IllegalStateException` is thrown anywhere in `copy/`
- [X] T103 [P] Document the architecture, the two strategies, and the store-responsibility split in [README.md](../../README.md), linking [plan.md](./plan.md) and [research.md](./research.md)
- [X] T104 Add PostgreSQL and Redis readiness to the CI workflow in [.github/workflows/github-actions.yml](../../.github/workflows/github-actions.yml) if `docker compose --wait` proves insufficient
- [ ] T105 Run the full suite with `mvn clean install` and confirm all 15 quickstart scenarios pass, covering all 23 success criteria
- [X] T106 Record the AWS SDK bump and the two new infrastructure dependencies in [README.md](../../README.md), noting the Lombok/log4j2 idiom split and that converting the five pre-existing files is a deliberate one-commit follow-up (research.md R21)

---

## Dependencies & Execution Order

### Phase dependencies

| Phase | Depends on | Notes |
|---|---|---|
| 1 — Setup | — | T001→T002→T003→T004 is a **hard chain**; T001 alone breaks the existing tests |
| 2 — Foundational | Phase 1 | Blocks every story. T107 verifies T019/T025/T026 and may be written in parallel with them |
| 3 — US1 (P1) | Phase 2 | 🎯 MVP core |
| 4 — US2 (P1) | Phase 2 | Independent of US1 |
| 5 — US3 (P1) | Phase 2 + T051 | Needs the verification gate from US1 |
| 6 — Ingress | Phase 2 | Independent of all stories |
| 7 — US4 (P2) | Phases 3, 4 | Compares strategies, so both must exist |
| 8 — US5 (P2) | Phase 2 (scenario 1); Phase 3 (scenario 2) | |
| 9 — US6 (P3) | Phases 3, 8 | Routes between both upload paths |
| 10 — US7 (P3) | Phase 3 | Reaps chunked-upload state |
| 11 — Polish | All desired phases | |

### Story independence

- **US1** and **US2** are genuinely independent and can be built in parallel by different people once Phase 2 lands.
- **US3** needs only T051 from US1 (the verification gate), not the whole story.
- **Ingress** is independent of every story — tests publish to the topic directly.
- **US4** is the one story that inherently depends on others: it compares two strategies, so both must exist.

### Parallel opportunities

| Group | Tasks |
|---|---|
| Foundational, independent files | T011, T013, T014, T017, T021, T023, T026, T027, T028, T031, T032, T033, T107 |
| US1 tests | T035, T036, T037, T038 |
| US1 independent implementation | T039, T040, T044, T050, T053 |
| US2 | T056, T062, T063 (after T054, T055) |
| Ingress | T071, T079, T080 |
| Polish | T100, T101, T102, T103 |
| Cross-phase | Once Phase 2 is done: US1, US2, and Ingress proceed concurrently |

---

## Parallel Example: User Story 1

```bash
# Write all four US1 test classes together — different files, no shared state:
Task: "ChunkPlanTest in src/test/java/com/reuven/kafka/demo/copy/ChunkPlanTest.java"
Task: "ResumableUploadIntegrationTest in src/test/java/com/reuven/kafka/demo/copy/ResumableUploadIntegrationTest.java"
Task: "CheckpointLifecycleTest in src/test/java/com/reuven/kafka/demo/copy/CheckpointLifecycleTest.java"
Task: "CredentialRenewalTest in src/test/java/com/reuven/kafka/demo/copy/CredentialRenewalTest.java"

# Then the independent implementation pieces:
Task: "TransferCheckpoint and ChunkConfirmation records in copy/checkpoint/"
Task: "CheckpointStore interface in copy/checkpoint/CheckpointStore.java"
Task: "ChunkPlan in copy/delivery/ChunkPlan.java"
Task: "IntegrityVerifier in copy/delivery/IntegrityVerifier.java"
```

---

## Implementation Strategy

### MVP scope

**Phases 1–5 (T001–T069): Setup + Foundational + the three P1 stories.**

The three P1 stories are jointly the MVP, not just US1. US1 without US2 is a resumable transfer nothing feeds; US2 without US1 is a slower inline consumer; and US3 is what prevents a bug in either from destroying a recording permanently. The spec ranks all three P1 for exactly that reason.

Stopping at T069 gives a system that consumes in batches, stages durably, delivers asynchronously with resumable chunked uploads, and releases the source only when safe. It cannot yet receive real provider notifications (Phase 6) and has no below-threshold fast path (Phase 8).

### Incremental delivery

1. Phases 1–2 → foundation; existing tests still green (T004 is the gate)
2. Phase 3 → US1 → validate SC-001, SC-002, SC-003, SC-016, SC-017
3. Phase 4 → US2 → validate SC-004, SC-005, SC-006, SC-013
4. Phase 5 → US3 → validate SC-018 — **the hard zero**
5. Phase 6 → ingress → validate SC-021, SC-022, SC-023
6. Phases 7–10 → US4–US7 → validate the remaining criteria
7. Phase 11 → polish and full-suite run

### Parallel team strategy

After Phase 2, three work streams run cleanly:

- **A**: US1 (Phase 3), then US7 (Phase 10) — both own the chunked path
- **B**: US2 (Phase 4), then US5 and US6 (Phases 8–9) — both own the delivery routing
- **C**: Ingress (Phase 6), then US3 (Phase 5, after T051)

US4 (Phase 7) is integration work for whoever finishes first, since it needs both strategies present.

---

## Notes

- `[P]` = different files, no dependency on incomplete work
- Verify each test fails before implementing against it
- Commit after each task or logical group
- Every new file gets `git add` immediately on creation — an unstaged new file is invisible in the IDE commit panel and has caused CI compile failures in this repo before
- Stop at any checkpoint to validate a story independently
- **T051 and T067 are the two tasks to review hardest.** Together they are the only thing standing between a finalization bug and permanent, unrecoverable data loss at the provider
