# Phase 0 Research: Kafka Consumer Network-Timeout Resilience

## 1. How to detect sustained network instability without a manual state machine

**Decision**: Use Resilience4j's `CircuitBreaker` (annotation-driven via `@CircuitBreaker(name = "s3Upload")` on `S3EventArchiveService.archive`), configured with a count-based sliding window (`sliding-window-size: 10`, `minimum-number-of-calls: 5`, `failure-rate-threshold: 50`). The breaker's own internal state machine (CLOSED → OPEN → HALF_OPEN → CLOSED) tracks the sustained failure pattern; application code never inspects raw call history itself.

**Rationale**: Resilience4j already implements exactly the "sustained pattern, not a single blip" requirement (FR-002, FR-009, SC-005) via its sliding window and minimum-calls threshold. Reimplementing this with a manual counter/state field would duplicate a well-tested library and reintroduce the "manually-wired state-transition listener" pattern the user explicitly wants to avoid.

**Alternatives considered**:
- Manual counter + `AtomicInteger`/`AtomicBoolean` flag flipped by application code inspecting exception types — rejected: exactly the "manual state-transition listener" the feature explicitly asks to move away from, more code to maintain, and re-implements what Resilience4j already provides.
- Spring Retry's `CircuitBreaker` (`spring-retry`) — rejected: less expressive sliding-window/half-open configuration than Resilience4j, and the project already depends on `resilience4j-spring-boot3`.
- Hystrix — rejected: in maintenance mode/deprecated, not recommended for new development.

## 2. How to pause/resume the Kafka consumer in reaction to circuit-breaker state, "without lag"

**Decision**: `KafkaBackpressureController` registers directly on the circuit breaker's `EventPublisher` (`circuitBreakerRegistry.circuitBreaker("s3Upload").getEventPublisher().onStateTransition(...)`) at construction time, and calls `MessageListenerContainer.pause()` / `.resume()` on `CLOSED_TO_OPEN`/`HALF_OPEN_TO_OPEN` and `OPEN_TO_HALF_OPEN`/`HALF_OPEN_TO_CLOSED` respectively.

**Rationale**: The event publisher fires synchronously and in-process the instant the breaker transitions state — there is no polling delay, satisfying FR-003 ("stop pulling new messages promptly ... without waiting for ... a fixed polling delay to elapse") and SC-001. Spring Kafka's `MessageListenerContainer.pause()` stops the container from polling for new records on its next poll loop iteration while letting in-flight records finish processing (satisfying FR-006), and `resume()` re-enables polling — this is the standard Spring Kafka mechanism for consumer backpressure, requiring no custom listener rebalancing logic.

**Alternatives considered**:
- Manually implementing a `CircuitBreaker.EventConsumer` wired via `@EventListener` on a Spring `ApplicationEvent` — functionally similar but adds an indirection layer (Resilience4j → Spring event bus → handler) with no benefit over subscribing to the native event publisher directly.
- Polling a health-check endpoint/flag on a fixed schedule (e.g., `@Scheduled` every N seconds) to decide whether to pause/resume — rejected: this is precisely the "polling delay" / non-modern approach the spec's FR-003 and the user's request explicitly rule out; introduces up to N seconds of lag in both directions.
- Stopping/starting the listener container entirely (`container.stop()`/`start()`) instead of `pause()`/`resume()` — rejected: stop/start tears down and recreates consumer group membership/partition assignment, causing unnecessary rebalances; `pause()`/`resume()` is the lighter-weight, purpose-built API for this exact scenario.

## 3. Automatic recovery via trial calls (half-open state)

**Decision**: Rely on Resilience4j's built-in HALF_OPEN behavior: `wait-duration-in-open-state: 15s` and `automatic-transition-from-open-to-half-open-enabled: true` cause the breaker to automatically transition OPEN → HALF_OPEN after 15s, at which point it permits `permitted-number-of-calls-in-half-open-state: 3` trial calls through. If they succeed at the configured failure-rate threshold, it transitions to CLOSED (resume); if not, back to OPEN (stay paused, and the wait timer restarts).

**Rationale**: Matches FR-004/FR-005 and User Story 2's acceptance scenarios exactly — a small number of trial calls, not a full traffic burst, gate the resume decision, and this is entirely Resilience4j-native (no custom scheduling code needed).

**Alternatives considered**:
- Custom `@Scheduled` task issuing a manual "ping" call to S3 and flipping a flag — rejected: duplicates half-open behavior Resilience4j already provides, and reintroduces polling lag.

## 4. Ensuring in-flight/already-failing messages still reach DLT (not silently dropped)

**Decision**: The circuit breaker's fallback method (`onUploadUnavailable`) wraps the underlying failure in `S3ArchiveUnavailableException` and rethrows. This propagates out of the `@KafkaListener` method, so Spring Kafka's existing `@RetryableTopic` (3 attempts, exponential backoff) and DLT (`-dlt` suffix) handling — already in place for other failure types — takes over unchanged, per FR-006 and FR-010.

**Rationale**: Reuses the existing, already-tested retry/DLT pipeline instead of adding a parallel failure-handling path, keeping the circuit breaker scoped purely to "should we keep consuming," not "how do we handle this message's failure."

**Alternatives considered**:
- Swallowing the exception in the fallback and acknowledging the message — rejected: violates FR-006 (must not silently discard); would lose the event permanently instead of routing it to the DLT for operator follow-up.

## 5. Bounding the S3 call itself so timeouts are detected promptly

**Decision**: `S3Config` sets `apiCallTimeout` and `apiCallAttemptTimeout` (from `aws.s3.api-call-timeout: 10s`) on the AWS SDK v2 `S3Client`'s `ClientOverrideConfiguration`, and `record-exceptions: [software.amazon.awssdk.core.exception.SdkClientException]` tells the circuit breaker to count SDK-level timeout/connectivity exceptions as failures.

**Rationale**: Without an explicit client-side timeout, a hanging TCP connection to S3 could block a call far longer than the circuit breaker's sliding window would tolerate, delaying detection. Bounding the call ensures each failed call fails fast enough for the breaker to see the pattern within a few seconds, satisfying SC-001.

**Alternatives considered**:
- Relying on default SDK timeouts — rejected: AWS SDK v2 defaults are generous (tens of seconds to minutes depending on connection acquisition), which would slow pattern detection well past the "few seconds" target in SC-001.

## 6. Scoping the pause to only the affected flow (FR-007)

**Decision**: `KafkaBackpressureController` looks up and pauses only the specific listener container by ID (`KafkaConsumer.LISTENER_ID = "myEventListener"`) via `KafkaListenerEndpointRegistry.getListenerContainer(id)`, rather than iterating over/pausing all registered containers.

**Rationale**: Directly satisfies FR-007 — only the flow tied to the affected network target (S3) is paused; since this application currently has a single consumer flow, this also keeps the mechanism trivially extensible (one controller per circuit-breaker-name/listener-id pair) if additional flows are added later, per the spec's Assumptions section.
