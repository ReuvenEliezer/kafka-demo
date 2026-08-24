---

description: "Task list for Kafka Consumer Network-Timeout Resilience"
---

# Tasks: Kafka Consumer Network-Timeout Resilience

**Input**: Design documents from `/specs/001-kafka-timeout-resilience/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Included — an integration test validating the pause/resume behavior end-to-end was part of this feature's scope (quickstart.md's primary validation path).

**Status**: All tasks below are already implemented and verified in the working tree (staged, uncommitted). This file documents the work as executed, for traceability against spec.md/plan.md.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Path Conventions

Single project: `src/main/java/com/reuven/kafka/demo/`, `src/test/java/com/reuven/kafka/demo/`, `src/main/resources/`, `src/test/resources/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependencies and configuration properties needed by the feature

- [X] T001 Add `resilience4j-spring-boot3` and pin `resilience4j.version` in [pom.xml](../../pom.xml)
- [X] T002 Add `spring-boot-starter-aop` in [pom.xml](../../pom.xml) — **required** for the `@CircuitBreaker` annotation aspect to actually be woven in; missing this made the circuit breaker inert (found and fixed during validation)
- [X] T003 [P] Add AWS SDK v2 `s3` dependency + BOM in [pom.xml](../../pom.xml)
- [X] T004 [P] Add Testcontainers `kafka`/`localstack`/`junit-jupiter` test dependencies and pin `testcontainers.version` to 1.21.4 in [pom.xml](../../pom.xml) — newer patch needed for compatibility with current Docker Engine API versions
- [X] T005 [P] Add `aws.s3.*` (region, bucket, endpoint, api-call-timeout) and `resilience4j.circuitbreaker.instances.s3Upload.*` config in [application.yaml](../../src/main/resources/application.yaml)

**Checkpoint**: Dependencies and config keys available for all stories

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure all three user stories build on

- [X] T006 Create `S3Client` bean with bounded `apiCallTimeout`/`apiCallAttemptTimeout` in [S3Config.java](../../src/main/java/com/reuven/kafka/demo/config/S3Config.java) — bounds each S3 call so failures surface fast enough for the circuit breaker's sliding window to detect a pattern within seconds (SC-001)
- [X] T007 Create `S3ArchiveUnavailableException` in [S3ArchiveUnavailableException.java](../../src/main/java/com/reuven/kafka/demo/services/S3ArchiveUnavailableException.java) — carries circuit-breaker fallback failures into the existing retry/DLT pipeline

**Checkpoint**: Foundation ready — user story implementation can now proceed

---

## Phase 3: User Story 1 - Stop wasting effort during a network outage (Priority: P1) 🎯 MVP

**Goal**: Detect sustained S3 timeouts and pause the Kafka consumer promptly, without dropping in-flight messages

**Independent Test**: Simulate S3 timing out on every call; confirm the flow stops consuming new messages after a short run of consecutive timeouts, while in-flight messages still complete retry/DLT handling

### Implementation for User Story 1

- [X] T008 [US1] Wrap S3 upload with `@CircuitBreaker(name = "s3Upload", fallbackMethod = "onUploadUnavailable")` and implement the fallback (throws `S3ArchiveUnavailableException`) in [S3EventArchiveService.java](../../src/main/java/com/reuven/kafka/demo/services/S3EventArchiveService.java) (depends on T006, T007)
- [X] T009 [US1] Register a `CircuitBreakerOnStateTransitionEvent` listener that pauses the `myEventListener` `MessageListenerContainer` on `CLOSED_TO_OPEN`/`HALF_OPEN_TO_OPEN` in [KafkaBackpressureController.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaBackpressureController.java) (depends on T008)
- [X] T010 [US1] Expose `LISTENER_ID` constant and confirm uncaught exceptions from `archive()` propagate into the existing `@RetryableTopic`/DLT handling (no swallowing) in [KafkaConsumer.java](../../src/main/java/com/reuven/kafka/demo/services/KafkaConsumer.java) (depends on T008)

**Checkpoint**: Sustained S3 outages now pause consumption promptly; in-flight messages still retry/dead-letter normally — User Story 1 is independently testable

---

## Phase 4: User Story 2 - Resume automatically once the network recovers (Priority: P2)

**Goal**: Automatically resume consumption once S3 is healthy again, via bounded trial calls rather than a full-traffic retry

**Independent Test**: After a simulated outage that paused the flow, restore S3 and confirm the flow resumes within a short, bounded time with no manual action

### Implementation for User Story 2

- [X] T011 [US2] Configure half-open recovery behavior (`wait-duration-in-open-state`, `permitted-number-of-calls-in-half-open-state`, `automatic-transition-from-open-to-half-open-enabled`) for the `s3Upload` instance in [application.yaml](../../src/main/resources/application.yaml) (depends on T005)
- [X] T012 [US2] Handle `OPEN_TO_HALF_OPEN`/`HALF_OPEN_TO_CLOSED` transitions by resuming the paused container (only if currently paused) in [KafkaBackpressureController.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaBackpressureController.java) (depends on T009, T011)

**Checkpoint**: Recovery is automatic and trial-gated — User Stories 1 and 2 both work independently

---

## Phase 5: User Story 3 - Visibility into pause/resume behavior (Priority: P3)

**Goal**: Give operators a clear, distinguishable log signal at the moment of pausing and resuming, including the reason

**Independent Test**: Trigger a simulated outage and recovery; confirm a clear log entry is produced at each transition

### Implementation for User Story 3

- [X] T013 [US3] Log an ERROR-level "network instability detected ... pausing" line on pause transitions and an INFO-level "network recovering ... resuming" line on resume transitions, including the Resilience4j state-transition name, in [KafkaBackpressureController.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaBackpressureController.java) (depends on T009, T012)

**Checkpoint**: All three user stories are independently functional and observable

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: End-to-end verification across all stories

- [X] T014 Write `S3CircuitBreakerIntegrationTest` (Testcontainers Kafka + LocalStack) covering healthy baseline → simulated outage → pause → recovery → resume in [S3CircuitBreakerIntegrationTest.java](../../src/test/java/com/reuven/kafka/demo/S3CircuitBreakerIntegrationTest.java) and matching [test application.yaml](../../src/test/resources/application.yaml) overrides (depends on T008–T013)
- [X] T015 Fix `S3CircuitBreakerIntegrationTest`'s Kafka container: replace the `confluentinc/cp-kafka` image + Bitnami-style env vars (incompatible with the modern `org.testcontainers.kafka.KafkaContainer` class) with `apache/kafka:3.7.1` in [S3CircuitBreakerIntegrationTest.java](../../src/test/java/com/reuven/kafka/demo/S3CircuitBreakerIntegrationTest.java) — found and fixed during validation
- [X] T016 Run `quickstart.md` automated validation (`mvn test -Dtest=S3CircuitBreakerIntegrationTest`) — **passing** as of the T002/T015 fixes

- [X] T017 Leave the `DefaultErrorHandler`/`DeadLetterPublishingRecoverer` bean in [KafkaConsumerConfig.java](../../src/main/java/com/reuven/kafka/demo/config/KafkaConsumerConfig.java) commented out and unwired — documented as an intentional placeholder for a possible future alternate DLT path (uses `spring.kafka.consumer.suffix=".DLT"`), distinct from the `@RetryableTopic`-driven `"-dlt"` path on `KafkaConsumer.listen(...)` that actually handles retry/DLT for this feature (see data-model.md's Message Failure section and [plan.md](./plan.md)'s file list). No functional change — this task only records the decision so the dead code isn't mistaken for unfinished or accidental scope creep.

**Checkpoint**: Feature verified end-to-end via `mvn test -Dtest=S3CircuitBreakerIntegrationTest` (BUILD SUCCESS)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies
- **Foundational (Phase 2)**: Depends on Setup — blocks all user stories
- **User Story 1 (Phase 3)**: Depends on Foundational — no dependency on US2/US3
- **User Story 2 (Phase 4)**: Depends on Foundational; extends the same controller class as US1 (T009) but is a distinct, independently testable behavior (resume vs. pause)
- **User Story 3 (Phase 5)**: Depends on Foundational; adds logging to the same transitions US1/US2 already handle — independently testable via log assertions alone
- **Polish (Phase 6)**: Depends on all three stories

### Notes on Shared File

US1, US2, and US3 all touch `KafkaBackpressureController.java` because pause, resume, and their log lines are three facets of one small event handler reacting to the same `CircuitBreakerOnStateTransitionEvent` stream — splitting them into separate files would fragment one cohesive state machine. Each story's slice (pause logic / resume logic / logging) is independently reviewable and testable via the transition-to-action mapping table in [data-model.md](./data-model.md).

### Parallel Opportunities

- T001–T005 (Setup) are largely independent (different config sections) and marked [P] where they touch different files
- T003 and T004 (AWS SDK deps vs. Testcontainers deps) are unrelated additions to `pom.xml` and can be done in either order

---

## Implementation Strategy

### MVP (already delivered)

Phase 1 → Phase 2 → Phase 3 (User Story 1) constitutes the MVP: sustained S3 outages pause the consumer. This is independently deployable/demoable on its own — User Stories 2 and 3 are additive safety/observability layers on top of it, both already implemented in this codebase.

### Incremental Delivery (as built)

1. Setup + Foundational → S3 client + exception type ready
2. User Story 1 → circuit breaker trips and pauses the consumer (MVP)
3. User Story 2 → automatic resume via half-open trial calls
4. User Story 3 → operator-visible log transitions
5. Polish → integration test validates all three end-to-end; two real bugs (missing AOP starter, wrong Kafka test image) were found and fixed during this phase
