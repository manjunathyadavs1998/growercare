# Tech Stack

## 1. Backend
| Concern | Choice | Notes |
|---|---|---|
| Language/runtime | **Java 21 LTS** (evaluate Java 25 LTS at kickoff) | Virtual threads for blocking I/O at high concurrency |
| Framework | **Spring Boot 4.1.x** (pinned: 4.1.2 via the parent POM; Spring Framework 7) | Spring MVC + virtual threads; WebFlux only in `edge-gateway` and `realtime-gateway` |
| Build | **Maven multi-module** (ADR-014), parent POM + dependency management | One BOM for all services; enforcer bans ORM dependencies |
| Persistence | **Spring `JdbcClient` + named SQL files, no ORM** (ADR-013, supersedes JPA/Hibernate multi-tenancy) | Shard schema from `ShardRouter`, validated; datasource per physical cluster |
| Migrations | Flyway with placeholders (`country_ix`, `shard_ix`, `cc`, `tz`) | Orchestrated rollout across shards (skill: `sharded-migration`) |
| DB | **Amazon RDS / Aurora PostgreSQL 16** | PostGIS in grower/field; RLS on partner-org tables |
| Security | Spring Security OAuth2 Resource Server; Keycloak (OIDC) | Short-lived JWTs; service-to-service via client credentials |
| Messaging | Spring for Apache Kafka, **Avro** + Schema Registry (AWS Glue SR or Confluent SR) | BACKWARD compatibility enforced in CI |
| CDC | **Debezium** PostgreSQL connector (pgoutput) on MSK Connect | Two connectors per cluster: tables → Snowflake; outbox router → domain topics |
| Warehouse | **Snowflake** (account per region), Snowflake Kafka connector (Snowpipe Streaming) | RAW → CURATED → SERVING |
| Cache | **ElastiCache (Redis OSS / Valkey), cluster mode** | Redisson or Lettuce; Bucket4j for rate limits |
| Resilience | Resilience4j | Circuit breaker, bulkhead, retry, time limiter |
| Observability | Micrometer + OpenTelemetry SDK/agent → OTel Collector → Amazon Managed Prometheus, Grafana, tracing backend | Mandatory span attributes: `cell`, `country`, `service` |
| API | OpenAPI 3.1, contract-first, OpenAPI Generator (server interfaces), springdoc for runtime docs | Problem Details (RFC 9457) |
| Mapping/validation | MapStruct, Jakarta Validation | |
| Integration | Apache Camel / Spring Integration (integration-service) | |
| Testing | JUnit 5, Mockito, AssertJ, **Testcontainers** (Postgres, Kafka, Redis), REST Assured, **Pact** (consumer-driven contracts), WireMock, **ArchUnit**, JaCoCo, k6 (load), Toxiproxy (fault injection) | Coverage gate ≥ 80% lines on domain/application layers |

## 2. Redis — usage rules
| Use | Key pattern | TTL / invalidation |
|---|---|---|
| Permission grants (policy-sdk) | `grants:{userId}` | 15 min + evict on `iam.grant.*` |
| Country profile / flags | in-process cache (bundle); Redis as second level | On `control.*` |
| Label sets | `label:{cc}:{crop}:{pest}` | 1 h + jitter, evict on `catalog.registration.changed` |
| Grower summary | `grower:{id}` | 30 min, evict on `grower.updated` |
| Scan verification | `code:{hash}`, Bloom filter `codes:bf` | Season-long; status change evicts |
| Rate limits | `rl:{scope}:{id}` | Bucket window |
| Idempotency keys | `idem:{service}:{key}` | 24 h (DB unique constraint stays the source of truth) |
| Token denylist | `jwt:deny:{jti}` | Until token expiry |
| Dashboard results | `dash:{widget}:{scopeHash}` | 5 min |
| Notification batch windows | `batch:case:{id}` | 5 min |

**Never in Redis:** case or comment writes, money state, saga state, anything that must survive a Redis failover.
**Stampede protection:** single-flight loading per key, TTL jitter ±10%, stale-while-revalidate for reference data.
**Topology:** one cluster per cell, Multi-AZ, ≥ 3 shards with replicas. Separate clusters for **rate-limit/session** and **cache** so cache eviction can't break rate limiting.

## 3. Kafka — usage rules
| Rule | Value |
|---|---|
| Cluster | Amazon MSK per cell, 3 AZs, RF=3, `min.insync.replicas=2`, producers `acks=all`, idempotent producers |
| Topic naming | `<domain>.<entity>.<event>` for domain events (e.g., `case.case.status_changed`); `cdc.<cell>.<schema>.<table>` for CDC; `dlq.<consumer-group>` |
| Keys | Aggregate ID (`case_id`, `claim_id`, …), so per-aggregate ordering is preserved |
| Partitions | Sized for the 1B-user target **upfront** (partitions can be added but that reshuffles keys): case/comment topics 96–192 per large cell; small domains 12 |
| Schemas | Avro, BACKWARD compatible, registered from `gc-contracts` CI |
| Retention | Domain events 7 days; CDC 7 days (PII limits); compacted topics for reference data |
| Consumers | Idempotent (inbox), manual commit after processing, retry topics with backoff, DLQ, KEDA autoscaling on lag |
| Envelope | CloudEvents-style headers: `event_id` (UUIDv7), `event_type`, `occurred_at`, `cell`, `country`, `trace_id`, `schema_version` |

## 4. Databases
| Store | Used for | Notes |
|---|---|---|
| PostgreSQL (RDS/Aurora) | All OLTP; per service; sharded for case/grower | 90-day hot window; weekly partitions; RLS on partner-org tables |
| Snowflake | Cold data (> 90 days after closure), analytics, dashboards, regulators' history | Account per region; row access + masking policies |
| Redis | Cache / rate limits / ephemeral state | Not a system of record |
| S3 | Media, config bundles, exports | Per cell; KMS-encrypted |
| OpenSearch (phase 3, optional) | Cross-shard text search for staff | Fed from CDC; not a system of record |
