# Implementation Plan: Kafka Consumer Network-Timeout Resilience

**Branch**: `001-kafka-timeout-resilience` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-kafka-timeout-resilience/spec.md`

## Summary

The `myEventListener` Kafka consumer archives each message to S3 via `S3EventArchiveService`. When S3 becomes slow/unreachable, per-message timeouts should not be retried blindly against a known-down target. A Resilience4j `CircuitBreaker` (`s3Upload`) wraps the S3 call and tracks a sliding window of outcomes; its state-transition events drive an event-listener component (`KafkaBackpressureController`) that pauses/resumes the `MessageListenerContainer` for the consumer — no manual polling loop or custom state machine. This is Resilience4j's built-in `CircuitBreakerOnStateTransitionEvent` publisher, which is the "modern" (annotation + event-driven) approach requested in place of a manually-wired listener/state machine.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.5.3, Spring Kafka, Resilience4j 2.2.0 (`resilience4j-spring-boot3`, AOP `@CircuitBreaker` annotation), AWS SDK v2 (S3, 2.28.11)

**Storage**: Amazon S3 (event archive target); no relational/NoSQL storage in scope

**Testing**: JUnit 5 (`spring-boot-starter-test`), Testcontainers (`kafka`, `localstack`, `junit-jupiter`) for integration testing against a real embedded Kafka broker and a LocalStack S3 endpoint

**Target Platform**: JVM server (Spring Boot application, Kafka consumer)

**Project Type**: Single project — Spring Boot service

**Performance Goals**: Detect sustained instability and pause consumption within a few seconds (SC-001); resume within a bounded, sub-minute window after recovery (SC-002)

**Constraints**: Must not introduce polling lag (react to circuit-breaker state transitions directly via event publisher, not a fixed-delay poll); must not affect unrelated failure types (e.g., malformed messages still go through the existing `@RetryableTopic`/DLT path); only the S3-dependent listener container is paused

**Scale/Scope**: Single Kafka consumer flow (`myEventListener`) calling a single downstream (S3); one circuit breaker instance (`s3Upload`)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

No project constitution is defined (`.specify/memory/constitution.md` is the unfilled template) — no gates apply. Proceeding directly to design based on spec requirements.

**Post-Phase-1 re-check**: No constitution to re-evaluate against; design in `research.md`/`data-model.md`/`quickstart.md` introduces no new external dependencies beyond what's already in `pom.xml` and reuses existing retry/DLT infrastructure rather than adding parallel mechanisms. Gate remains N/A.

## Project Structure

### Documentation (this feature)

```text
specs/001-kafka-timeout-resilience/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command)
├── quickstart.md         # Phase 1 output (/speckit-plan command)
├── contracts/            # Phase 1 output (/speckit-plan command) — skipped, no external API contract
└── tasks.md              # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
src/main/java/com/reuven/kafka/demo/
├── config/
│   ├── KafkaConsumerConfig.java          # Consumer factory, listener container factory, DLT error handler
│   ├── KafkaBackpressureController.java  # Subscribes to circuit breaker state-transition events; pauses/resumes the listener container
│   └── S3Config.java                     # S3Client bean with bounded api-call-timeout/api-call-attempt-timeout
├── services/
│   ├── KafkaConsumer.java                # @KafkaListener; delegates archiving to S3EventArchiveService
│   ├── S3EventArchiveService.java        # @CircuitBreaker(name="s3Upload")-wrapped S3 upload + fallback
│   └── S3ArchiveUnavailableException.java # Thrown by the circuit-breaker fallback so message failure still flows into @RetryableTopic/DLT handling
└── entities/
    └── MyEvent.java

src/main/resources/
└── application.yaml                      # resilience4j.circuitbreaker.instances.s3Upload tuning; aws.s3.* timeouts

src/test/java/com/reuven/kafka/demo/
└── S3CircuitBreakerIntegrationTest.java  # Testcontainers-based Kafka + LocalStack integration test

src/test/resources/
└── application.yaml                      # Test-profile overrides (LocalStack endpoint, faster CB thresholds, etc.)
```

**Structure Decision**: Single Spring Boot project (Option 1: single project). No new modules — the feature extends the existing `config`/`services` packages. This matches the existing codebase layout; no restructuring needed.

## Complexity Tracking

*No constitution violations — table not applicable.*
