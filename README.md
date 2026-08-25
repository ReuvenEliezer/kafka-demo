# kafka-demo

A Spring Boot service demonstrating two independent strategies for consuming a Kafka message that
requires a slow, fallible downstream write — here, copying a recording from a third-party provider into
S3. Both strategies are kept permanently, side by side, as a comparable example of the trade-off between
them.

## The two consumption strategies

| | **inline** (`copy.consumer.strategy=inline`, default) | **staged** (`copy.consumer.strategy=staged`) |
|---|---|---|
| Consumer | `KafkaConsumer` calls the S3 write synchronously, holding the message | `StagedBatchConsumer` writes each message to PostgreSQL and acknowledges immediately |
| Under an S3 outage | Pauses consumption via `KafkaBackpressureController`'s circuit breaker — topic lag accrues | Keeps consuming — a durable staged backlog accrues instead |
| Delivery | Happens inline, before the offset commits | Happens later, off a background `DeliveryWorker` polling the staging table |
| Large payloads | Single `PutObject` regardless of size | Resumable multipart upload above a configurable threshold (default 100 MB), checkpointed in Redis |
| Infrastructure when inactive | None of the staged strategy's beans, JPA, or Redis are created | N/A |

Only one strategy is active in a given run: `copy.consumer.strategy` selects a Spring profile
(`copy-inline` / `copy-staged`) before configuration loads, which is what actually excludes the inactive
strategy's auto-configuration (`DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration`,
`RedisAutoConfiguration`, `FlywayAutoConfiguration` are all excluded under `copy-inline`) — see
`StrategyProfileActivator` and research.md R6. `StrategyComparisonTest` and `T084`'s audit both confirm a
clean inline start creates no JPA, Redis, or staged-worker beans at all.

### Running the inline strategy

```bash
docker compose up -d --wait
mvn spring-boot:run
```

No extra property is needed — `inline` is the default (`copy.consumer.strategy` unset or `inline`).
Only Kafka and S3/LocalStack are required.

### Running the staged strategy

```bash
docker compose up -d --wait
mvn spring-boot:run -Dspring-boot.run.arguments=--copy.consumer.strategy=staged
```

This strategy additionally needs PostgreSQL and Redis, both already defined with health checks in
[docker-compose.yml](docker-compose.yml).

### Seeing the behavioural contrast

Stop (or block) the S3/LocalStack endpoint while each strategy is running and watch the difference:

- **inline**: the circuit breaker trips, the Kafka listener container pauses, and consumer lag on the
  topic grows for as long as the outage lasts.
- **staged**: consumption never pauses; `DeliveryWorker` retries failed items in place while
  `staged_item` rows accrue in `PERMANENTLY_FAILED`/retry state instead. Once S3 recovers, delivery
  drains the backlog.

Both eventually deliver everything they acknowledged — the contrast is *where the backpressure shows up*,
not *whether data is lost*. This scenario is exercised end-to-end by
`S3CircuitBreakerIntegrationTest` (inline) and `StagedConsumerIntegrationTest` (staged).

## Architecture

```
Provider notification (HTTPS, HMAC-signed)
        │
        ▼
ProviderNotificationController  ──publishes──▶  Kafka topic (one message per recording file)
                                                         │
                        ┌────────────────────────────────┴───────────────────────────────┐
                        ▼ (copy-inline)                                                   ▼ (copy-staged)
                  KafkaConsumer                                                    StagedBatchConsumer
                        │                                                                 │
                        │ calls inline, holds the message                    writes to `staged_item` (Postgres),
                        │                                                     acknowledges immediately
                        ▼                                                                 │
                S3EventArchiveService  ──PutObject──▶  S3                                 ▼
                                                                                    DeliveryWorker (background poll)
                                                                                            │
                                                                              ┌─────────────┴─────────────┐
                                                                              ▼                            ▼
                                                                     SingleRequestUploader        ChunkedUploader
                                                                     (< threshold)                 (>= threshold)
                                                                              │                            │
                                                                              │                  checkpoint in Redis,
                                                                              │                  resumable across restarts
                                                                              ▼                            ▼
                                                                                        S3 (multipart)
```

### Store responsibility split (deliberate, not incidental)

- **PostgreSQL (`staged_item`) is authoritative for "does this still need delivering."** It is durable
  and transactional; a row's absence or `DELIVERED` state is the only thing that means *done*.
- **Redis is authoritative for nothing.** It holds resumable-upload checkpoints (confirmed chunk
  ordinals + ETags) under a sliding TTL. Checkpoint absence always means *restart from the beginning*,
  never *finished* — see [data-model.md](specs/002-staged-resumable-s3-consumer/data-model.md) and
  [plan.md](specs/002-staged-resumable-s3-consumer/plan.md) for why conflating those two would risk
  finalizing an empty object.

For the full design rationale — why `S3TransferManager` couldn't be used, why chunk confirmation and TTL
refresh are one atomic Lua call, why part size scales with payload size — see
[plan.md](specs/002-staged-resumable-s3-consumer/plan.md) and
[research.md](specs/002-staged-resumable-s3-consumer/research.md).

## Dependencies

The staged strategy (`specs/002-staged-resumable-s3-consumer`) added two infrastructure dependencies on
top of the existing stack:

- **PostgreSQL** (`spring-boot-starter-data-jpa`, Flyway, `postgresql` driver) — the staging table
- **Redis** (`spring-boot-starter-data-redis`) — resumable-upload checkpoints

It also bumped the **AWS SDK v2 from 2.28.11 to 2.35.10**, required for full-object multipart checksum
support (research.md R14).

New code in this feature follows Lombok + `@Slf4j` conventions (`record` DTOs, constructor injection via
`@RequiredArgsConstructor`, `java.time.Duration`/`Clock` over raw millis). The five pre-existing classes
from before this feature keep their original `LogManager.getLogger` idiom rather than being rewritten —
the spec's scope boundary preserves the inline strategy as-is. Converting those five files to the newer
idiom is a deliberate, separate one-commit follow-up rather than something bundled into this feature
(research.md R21).

## Swagger

- http://localhost:8080/swagger-ui/index.html
