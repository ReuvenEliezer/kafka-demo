# Quickstart: Validating Kafka Consumer Network-Timeout Resilience

This guide validates the feature end-to-end: sustained S3 timeouts pause the `myEventListener` Kafka consumer, and recovery resumes it automatically, per [spec.md](./spec.md) User Stories 1–3.

## Prerequisites

- Java 21, Maven
- Docker running locally (Testcontainers spins up a Kafka broker and a LocalStack S3 endpoint)
- No manual infrastructure setup needed — the automated test (below) provisions everything

## Automated validation (primary path)

The integration test [`S3CircuitBreakerIntegrationTest`](../../src/test/java/com/reuven/kafka/demo/S3CircuitBreakerIntegrationTest.java) exercises the full scenario against real containers:

```bash
mvn test -Dtest=S3CircuitBreakerIntegrationTest
```

**What it does** (maps to spec User Stories):

1. **Healthy baseline** — sends one message, confirms it's archived to S3 and the `s3Upload` circuit breaker stays `CLOSED` / consumer stays unpaused.
2. **Simulated outage** (User Story 1) — pauses the LocalStack Docker container so S3 calls hang until `aws.s3.api-call-timeout` elapses (producing `SdkClientException` timeouts), sends 4 more messages, then asserts:
   - the circuit breaker transitions to `OPEN` within 30s,
   - the Kafka listener container becomes paused within 10s of that.
3. **Automatic recovery** (User Story 2) — unpauses LocalStack, then asserts:
   - the circuit breaker returns to `CLOSED` within 15s (via its automatic `OPEN → HALF_OPEN` transition and successful trial calls),
   - the listener container resumes (unpaused) within 10s of that,
   - a new message sent after recovery is successfully archived.

**Expected outcome**: test passes; the assertions above are the machine-checkable equivalent of SC-001 and SC-002.

## Manual/exploratory validation

To watch the behavior directly (User Story 3 — visibility):

1. Start local Kafka + a LocalStack S3 endpoint (or point `aws.s3.endpoint` at any S3-compatible endpoint you control).
2. Run the app: `mvn spring-boot:run`
3. Produce a few normal messages to `my-topic-name` — confirm they're archived and no state-transition log lines appear.
4. Make the S3 endpoint unreachable (e.g., stop the LocalStack container, or block the port) and produce several more messages.
5. Watch the application logs for:
   ```
   ERROR ... S3 network instability detected - circuit breaker CLOSED_TO_OPEN. Pausing Kafka consumer
   ```
   This is the FR-008 transition event. Confirm no new "Received Message" log lines appear while paused (consumer has stopped polling), and confirm any message already in flight when the breaker tripped still completes its retry/DLT handling (SocketTimeoutException-driven `RuntimeException`s route to `<topic>-dlt`).
6. Restore the S3 endpoint. Within `wait-duration-in-open-state` (15s in `application.yaml`) plus a brief trial period, watch for:
   ```
   INFO ... S3 network recovering - circuit breaker OPEN_TO_HALF_OPEN. Resuming Kafka consumer
   ```
   or `HALF_OPEN_TO_CLOSED`, depending on timing — then confirm "Received Message" log lines resume and newly produced messages are archived again.

## Tuning reference

Circuit breaker thresholds live in `application.yaml` under `resilience4j.circuitbreaker.instances.s3Upload` (see [data-model.md](./data-model.md) for the full parameter table). Test-profile overrides in `src/test/resources/application.yaml` use tighter windows/timeouts so the integration test runs quickly.
