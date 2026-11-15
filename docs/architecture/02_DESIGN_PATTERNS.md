# Design Patterns — what we use, where, and why

| Pattern | Where | Why (problem it solves here) | Cost / watch-outs |
|---|---|---|---|
| **Hexagonal architecture (ports & adapters)** | Every service | Domain logic testable without DB/Kafka; compliance packs, ERP and Snowflake are adapters | More interfaces; enforced with **ArchUnit** rules so layers don't erode |
| **DDD bounded contexts & aggregates** | Service boundaries; `Case` aggregate (case + its comments/activity in one shard) | Clear ownership; consistency boundary = one shard transaction | Cross-aggregate rules become eventual |
| **Database per service** | All services | Independent schema evolution and scaling | No cross-service joins, so read models are built from events |
| **Sharding with ID-encoded routing** (Instagram) | case-service, grower-service | 200M cases/day; route any read by decoding the ID | Cross-shard queries are scatter-gather or go to Snowflake/OpenSearch |
| **Transactional Outbox + Debezium Outbox Event Router** | Every event publisher | Event published **iff** the business transaction commits; no dual writes | Outbox table housekeeping; Debezium is critical infrastructure |
| **Idempotent Consumer (inbox table)** | Every Kafka consumer | Kafka is at-least-once; replays must not double-notify or double-pay | Inbox growth (partitioned, 7-day retention) |
| **Saga — orchestrated** | claims-finance-service → integration-service → ERP | Multi-step money flow with compensation and one owner of state | Orchestrator complexity; persisted saga state |
| **Saga — choreographed** | Case closed → notification, Snowflake, realtime | Simple fan-out reactions; no central coordinator needed | Harder to see end-to-end flow, so tracing is mandatory |
| **State machine (table-driven, versioned)** | Case workflows per type × country; claims; visits | Explicit legal transitions; country variants by config | Migrating in-flight instances when definitions change |
| **Strategy / Plugin** | Compliance packs (`pack-in`, `pack-br`, `pack-us`, `pack-eu`) behind `compliance-pack-api` | Country rules without `if (country == …)` across the codebase | Plugin API stability; pack versioning |
| **CQRS** | Writes in Postgres shards; reads from Redis summaries, Snowflake SERVING, OpenSearch (phase 3) | Write model optimised for transactions; read models for lists, dashboards, archive | Eventual consistency; freshness shown in UI |
| **Optimistic locking** | Cases, claims, profiles | Concurrent edits by many actors; no long DB locks | 412/409 handling in UI |
| **API Gateway + BFF routes** | edge-gateway | One edge for auth, rate limits, routing per client type | Must not become a "god gateway" |
| **Circuit breaker / bulkhead / retry with jitter / timeout** (Resilience4j) | Every outbound sync call and external integration | Contain slow dependencies; prevent retry storms | Tuning per dependency; dashboards for breaker state |
| **Cache-aside + event-driven invalidation** | Grants, label sets, profiles, grower summaries, scan results | Read-heavy reference data at 100K+ req/s | Stampede protection (single-flight, jittered TTL); staleness bounds |
| **Anti-corruption layer** | integration-service | Keep ERP/regulator models out of our domain | One more translation layer |
| **Claim check** | Attachments referenced in events (S3 key, not bytes) | Kafka messages stay small | Consumers need S3 read rights |
| **Policy Enforcement Point / Policy Decision Point (ABAC)** | `policy-sdk` in every service + grants from identity-access-service | One authorization model across APIs, dashboards, realtime, Snowflake | Policy changes need versioning and a test matrix |
| **Competing consumers with key-ordered partitions** | All Kafka consumers | Horizontal scale while keeping per-case ordering | Hot keys can't be parallelised; partition count fixed upfront |
| **Cell-based architecture** | Deployment topology | Blast-radius isolation, data residency, independent scaling | Duplicate infrastructure per cell; cross-cell features need care |
| **Expand → migrate → contract** | All schema changes (1,280 shard schemas) | Zero-downtime migrations with mixed app versions | Changes take 2–3 releases |
| **Durable timers (`SKIP LOCKED` poller)** | SLA and regulatory clocks | Millions of timers that survive restarts | Poll interval vs precision |
| **Token bucket rate limiting** | Gateway, OTP, comments, scans | Abuse and cost control | Redis dependency |

## Patterns deliberately *not* used (yet)
| Pattern | Reason |
|---|---|
| Event sourcing for cases | Activity log + CDC already gives history; event sourcing at 200M aggregates/day adds replay and snapshot complexity |
| Service mesh (Istio/Linkerd) | Operational cost; NetworkPolicies + OAuth2 client credentials first (ADR-010) |
| Micro-frontends | Team count doesn't justify it yet (see `06_FRONTEND.md`) |
| Distributed transactions (2PC/XA) | Don't scale; outbox + sagas instead |
| GraphQL federation | REST + BFF routes are enough; revisit if UI aggregation pain grows |
