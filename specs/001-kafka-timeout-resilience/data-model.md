# Phase 1 Data Model: Kafka Consumer Network-Timeout Resilience

This feature is a control-plane/resilience mechanism, not a data-persistence feature — there are no new database entities or schemas. The "entities" below are the in-memory/runtime concepts introduced or coordinated by this feature, mapped to their concrete implementation.

## Network Health State

Represents whether the downstream S3 target is currently considered healthy, unstable, or recovering.

| Attribute | Description | Implementation |
|---|---|---|
| State | One of CLOSED (healthy), OPEN (unstable/paused), HALF_OPEN (recovering/trial) | `io.github.resilience4j.circuitbreaker.CircuitBreaker.State`, instance name `s3Upload` |
| Sliding window | Recent call outcomes used to compute the failure rate | Count-based, size 10 (`resilience4j.circuitbreaker.instances.s3Upload.sliding-window-size`) |
| Minimum calls | Calls required before the failure rate is evaluated | 5 (`minimum-number-of-calls`) |
| Failure rate threshold | % of failed calls in the window that trips CLOSED → OPEN | 50% (`failure-rate-threshold`) |
| Open-state wait duration | Time spent OPEN before auto-transitioning to HALF_OPEN | 15s (`wait-duration-in-open-state`) |
| Half-open trial calls | Number of calls permitted through while HALF_OPEN | 3 (`permitted-number-of-calls-in-half-open-state`) |
| Recorded failure types | Exceptions counted as failures for the sliding window | `software.amazon.awssdk.core.exception.SdkClientException` (covers S3 API-call/attempt timeouts and connectivity failures) |

**State transitions** (all handled internally by Resilience4j; the application only reacts to the resulting events):

```
CLOSED --(failure-rate ≥ threshold over window)--> OPEN
OPEN --(wait-duration elapses, auto-transition enabled)--> HALF_OPEN
HALF_OPEN --(trial calls succeed)--> CLOSED
HALF_OPEN --(trial calls fail)--> OPEN
```

## Flow (Consumer)

The specific Kafka message-processing pipeline whose consumption is paused/resumed based on Network Health State.

| Attribute | Description | Implementation |
|---|---|---|
| Listener ID | Identifies the Spring Kafka listener container to pause/resume | `KafkaConsumer.LISTENER_ID = "myEventListener"` |
| Topic | Source topic the flow consumes from | `${spring.kafka.topic}` (`my-topic-name`) |
| Container | The runtime object exposing `pause()`/`resume()`/`isContainerPaused()` | `org.springframework.kafka.listener.MessageListenerContainer`, looked up via `KafkaListenerEndpointRegistry.getListenerContainer(LISTENER_ID)` |
| Paused | Whether the container is currently not polling for new records (in-flight records still complete) | `MessageListenerContainer.isContainerPaused()` |

**Relationship**: One Flow (Consumer) is associated with exactly one Network Health State (the `s3Upload` circuit breaker) in the current scope — a 1:1 mapping enforced by `KafkaBackpressureController` hard-coding both the breaker name and the listener ID it controls.

## State Transition Event

A recorded occurrence of the Network Health State changing, used for operator visibility (FR-008).

| Attribute | Description | Implementation |
|---|---|---|
| From state / To state | The transition, e.g. CLOSED_TO_OPEN | `CircuitBreakerOnStateTransitionEvent.getStateTransition()` (an enum covering all pairwise transitions) |
| Reason | Human-readable cause, always "network instability" for OPEN transitions in this flow | Logged message text in `KafkaBackpressureController.handleStateTransition` |
| Timestamp | When the transition occurred | Implicit in the log line's timestamp (via Log4j2 pattern layout); Resilience4j's event also carries a `creationTime` |
| Action taken | Whether the container was paused or resumed as a result | Logged alongside the transition (`"Pausing Kafka consumer"` / `"Resuming Kafka consumer"`) |

**Mapping of transitions to actions**:

| Transition | Action | Log level |
|---|---|---|
| `CLOSED_TO_OPEN`, `HALF_OPEN_TO_OPEN` | `container.pause()` | ERROR |
| `OPEN_TO_HALF_OPEN`, `HALF_OPEN_TO_CLOSED` | `container.resume()` (only if currently paused) | INFO |
| all other transitions (e.g. `DISABLED_TO_CLOSED`) | no action | — |

No persistence/database entity is created for these events in this feature — logs (and, transitively, Resilience4j's own registered metrics if a metrics backend is added later) are the system of record for FR-008/SC-004.

## Message Failure (existing, unchanged)

Not introduced by this feature, but explicitly interacted with per FR-006/FR-010. `S3ArchiveUnavailableException`, thrown from the circuit breaker's fallback method, propagates out of `KafkaConsumer.listen(...)` like any other uncaught exception, so it flows into the existing `@RetryableTopic` (3 attempts, exponential backoff, `-dlt` suffix) handling already in place for other failure types (e.g., the deliberate `RuntimeException` used for testing). A message whose S3 call failed while the breaker was still CLOSED (or during a HALF_OPEN trial) continues to retry and eventually dead-letter exactly as any other processing failure does, unaffected by the pause/resume mechanism. (Note: `KafkaConsumerConfig`'s `DefaultErrorHandler`/`DeadLetterPublishingRecoverer` bean is a second, currently-unused retry path wired into the container factory — `@RetryableTopic` is the one actually driving retry/DLT behavior for this listener today.)
