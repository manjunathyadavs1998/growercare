# SPEC-PLAT-04: Event publishing (outbox → Debezium → Kafka) and idempotent consumers

> **Paths after ADR-017 (2026-11-16).** This document was written for the `gc-backend` monorepo. The rules still hold; the paths moved:
> `gc-backend/services/<svc>` → repository `growercare-<svc>` · `gc-backend/contracts/<svc>.openapi.json` → `contracts/` in that repository · `gc-backend/contracts/events/` → `gc-event-contracts` in growercare-platform-libs · `gc-backend/local-dev/` (incl. `smoke.sh`) → growercare-local-platform · `gc-backend/coverage` → per repository (services `target/site/jacoco-merged/jacoco.xml`, libs `coverage/target/site/jacoco-aggregate/jacoco.xml`) · workflow `backend-ci` → the kit's `ci.yml` (check `verify`). See SPEC-PLAT-06.

- **Requirements:** ADR-001 (async integration), ADR-012 (topics/keys), `02_DESIGN_PATTERNS.md` (transactional outbox + Debezium outbox router, idempotent consumer), CLAUDE.md conventions 3 and 5; IAM ACC-1 (grant changes take effect promptly)
- **Status:** Implemented (agreed 2026-11-15; D2 = JSON + JSON Schema; smoke E71–E74 green 2026-11-16)
- **Owner:** gc-outbox (library), identity-access-service, case-service, local-dev
- **Depends on:** SPEC-IAM-01 (grant events), SPEC-CASE-02/03/04 (case events), SPEC-CASE-04 R91–R95 (per-shard jobs)

## 0. Decisions to agree
| # | Decision | Alternative (not chosen) |
|---|---|---|
| D1 | **Local broker:** a single-node Apache Kafka (KRaft, no ZooKeeper) plus **Debezium Kafka Connect** in `docker-compose`. These are the same products as MSK and MSK Connect in AWS. The exact image versions are pinned when work starts | Redpanda (not the production broker); a Java relay that polls the outbox (dual mechanism; contradicts ADR-012 and the patterns doc) |
| D2 | **Event payload = JSON** (the envelope already stored in `outbox.payload`). Contracts are **JSON Schema** files in `gc-backend/contracts/events/`, and tests check every emitted event against them. **Avro + Glue Schema Registry** are deferred to the AWS stage. **ADR-015** records this and supersedes ADR-012's "Avro" bullet only | Avro now with a local schema registry: one more container, and the generic `jsonb` payload has no typed Avro schema until every event gets its own record |
| D3 | **Topics per aggregate:** `<domain>.<aggregate>.events`, e.g. `case.case.events` and `iam.grant.events`. Every event of one aggregate shares one topic, because consumers need **per-case ordering** (created → routed → status_changed → sla.*). The event type travels in a header. This is the documented exception in the event-contract skill (one type per topic unless the events share ordering) | One topic per event type: loses the cross-type ordering for a case |
| D4 | **Outbox housekeeping:** Debezium reads the WAL, so outbox rows are only kept for debugging and replay. A daily job deletes rows older than **3 days**, per shard and with the advisory try-lock (same pattern as SPEC-CASE-04 R91–R95). The app role gets `DELETE` on `outbox` only | Insert and delete in the same transaction (the Debezium "outbox-less" trick): hides events from support while debugging |
| D5 | **Inbox:** `gc-outbox` gains an `InboxDeduplicator`. A table `msg.inbox (consumer_group, event_id, processed_at)` with PK (consumer_group, event_id) is written **in the same transaction** as the side effect, and a duplicate is skipped. Retention is 7 days (daily job) | Dedup in Redis (Redis is never a system of record, convention 5) |
| D6 | **First consumer:** case-service, group `case-service.iam-grant-cache`, reads `iam.grant.events`. `iam.grant.created` and `iam.grant.revoked` **evict the cached principal** of `subject`. Today a revoked grant can stay effective for up to 5 minutes of cache TTL; after this it lasts only for the event lag. Unknown event types are ignored (forward compatible) | Shorter TTL only: more IAM calls and still stale |
| D7 | **Consumer errors:** manual commit after processing; **3 in-process retries with backoff** (1 s, 2 s, 4 s); then the record goes to **`dlq.case-service.iam-grant-cache`** with the original headers plus the exception class and message. Delayed retry topics (1 min / 10 min / 1 h) arrive with the first consumer whose side effect is slow or external (notification-service) | Retry topics now: three extra topics for an idempotent cache eviction |
| D8 | **CDC plumbing as code:** <br>• Postgres publication `gc_outbox` created by Flyway (IAM V5; case common migration + every shard schema adds its `outbox`) with `publication.autocreate.mode=disabled`; <br>• a dedicated login role `gc_cdc` (REPLICATION + `SELECT` on the outbox tables only); <br>• connector configs `local-dev/debezium/{iam,case}-outbox.json`, registered by an idempotent `local-dev/debezium/register.sh` (`PUT /connectors/<name>/config`) | Connector auto-creating `FOR ALL TABLES`: would stream every table, PII included, into the outbox connector |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| PLAT-R50 | a business transaction appends an event | it commits | exactly one Kafka record on `<domain>.<aggregate>.events`; **key = aggregate id** (string); value = the JSON envelope `{eventId, eventType, occurredAt, data}`; headers `id`, `eventType`, `traceId` |
| PLAT-R51 | a business transaction appends an event | it rolls back | no Kafka record (outbox row never committed) |
| PLAT-R52 | the publication `gc_outbox` | after migrations | contains **exactly** the outbox tables + the CDC heartbeat table: `iam.outbox`, `iam.cdc_heartbeat` in gc_iam; every shard's `<schema>.outbox` + `msg.cdc_heartbeat` in gc_case. **No other table** (no PII tables reach the outbox connector). It publishes `insert, update` only, so outbox housekeeping deletes are never streamed |
| PLAT-R53 | role `gc_cdc` | — | has REPLICATION and `SELECT` on outbox tables only; `SELECT` on any other table is denied |
| PLAT-R54 | every event type emitted by the code | — | has a JSON Schema in `contracts/events/`, and a sample produced by the real use case validates against it; `data` contains **ids/codes only, no PII** (no name, phone, email) |
| PLAT-R55 | outbox rows older than 3 days and newer rows (case-service shards) | outbox housekeeping runs | the old rows are deleted, newer rows stay; per shard under the advisory try-lock; a failing shard is skipped (R95 of SPEC-CASE-04 applies). IAM's outbox (grant changes only, tiny) gets the same job when IAM gains scheduled jobs |
| PLAT-R56 | an event already in `msg.inbox` for the consumer group | it is delivered again | the side effect does **not** run again; offset committed |
| PLAT-R57 | a side effect that throws | the record is processed | the inbox row is rolled back with it (same transaction), so the next delivery processes it again |
| PLAT-R58 | `iam.grant.created` / `iam.grant.revoked` for subject S | consumed by case-service | the cached principal of S is evicted; S's next request re-reads grants from IAM |
| PLAT-R59 | an event type the consumer does not handle | consumed | ignored, recorded in the inbox, offset committed (forward compatible) |
| PLAT-R60 | a record that keeps failing | 3 retries exhausted (transient errors) **or immediately** (malformed: not JSON, no `eventId`/`eventType`, a grant event without a UUID `subject`) | published to `dlq.case-service.iam-grant-cache` with the original key, value and headers + Spring Kafka's standard DLT headers (`kafka_dlt-exception-cause-fqcn` = the real exception, `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-original-topic/partition/offset`); the partition continues with the next record |
| PLAT-R63 | a local database where the dev seed (V1000+) is already applied | a later **common** migration is added (e.g. case common V4/V5, IAM V5) | it is applied (Flyway `outOfOrder` **only when the dev seed is on**: local profile and tests; production never has the seed and keeps strict ordering) |
| PLAT-R64 | any main class | ArchUnit | no `org.hibernate` / `jakarta.persistence` (ADR-013). Replaces the ORM half of SPEC-PLAT-01 R7 |
| PLAT-R65 | any main class | ArchUnit | `org.springframework.kafka` / `org.apache.kafka` only in `..adapters.in.messaging..` (listeners) and `..config..` (error handler / DLQ). Business code still publishes **only through the outbox**. Replaces the Kafka half of SPEC-PLAT-01 R7 |
| PLAT-R62 | the CDC heartbeat | Debezium runs `heartbeat.action.query` every 10 s | a single-row upsert on `cdc_heartbeat` (role `gc_cdc` may write only that table), so the replication slot advances even when no event is written and WAL is not retained by idle outbox traffic |
| PLAT-R61 | inbox rows older than 7 days | inbox housekeeping runs | deleted; newer rows stay |

## 2. Examples (each row = one parameterized test row)
| Example | Rule | Input | Expected |
|---|---|---|---|
| PLAT-E60 | R52 | gc_case after all shard migrations (light test DB) | publication tables = { every shard's `outbox` } ∪ { `msg.cdc_heartbeat` }; publishes insert+update, not delete |
| PLAT-E61 | R52 | gc_iam after migrations | publication tables = { `iam.outbox`, `iam.cdc_heartbeat` } |
| PLAT-E62 | R53 | `gc_cdc` reads `in_s01.outbox` · `in_s01.cases` · `ref.grower` · `iam.outbox` · `iam.user_account` | allowed · denied · denied · allowed · denied |
| PLAT-E63 | R54 | each emitted type: `case.created`, `case.routed`, `case.assigned`, `case.status_changed`, `case.shared`, `case.unshared`, `comment.posted`, `case.sla.warning`, `case.sla.breached`, `iam.grant.created`, `iam.grant.revoked` | produced by the real use case; validates against its schema; no PII field names/values |
| PLAT-E64 | R55 | outbox rows aged 4 days and 1 day in one shard | 4-day row deleted, 1-day row kept |
| PLAT-E65 | R56 | the same `iam.grant.revoked` delivered twice | evictor called once; one inbox row |
| PLAT-E66 | R57 | eviction throws once, then succeeds | no inbox row after the failure; processed on the retry; one inbox row at the end |
| PLAT-E67 | R58 | `iam.grant.created` · `iam.grant.revoked` for `grower.in1`'s subject, principal cached before | cache entry gone (both rows) |
| PLAT-E68 | R59 | `iam.grant.renamed` (unknown) | no eviction; inbox row; offset committed |
| PLAT-E69 | R60 | value `not-json` · envelope without `eventId` · grant event without `subject` | each record on the DLQ topic with `kafka_dlt-exception-cause-fqcn` = `MalformedEventException`; the following valid record is still processed |
| PLAT-E75 | R60 | eviction always fails (Redis down) | after 3 retries the record is on the DLQ; the next record is processed |
| PLAT-E77 | R63 | **local stack:** the existing volume (case common history `1,2,3,1000,1001`; IAM `1,2,3,4,1000`) | case-service and IAM start; case common V4 + V5 and IAM V5 applied |
| PLAT-E76 | R62 | `gc_cdc` upserts `cdc_heartbeat` · inserts into an `outbox` | allowed · denied |
| PLAT-E70 | R61 | inbox rows aged 8 days and 1 day | 8-day row deleted, 1-day row kept |
| PLAT-E71 | R50 | **smoke (local stack):** admin.in grants a role → read `iam.grant.events` | one record, key = grantId, header `eventType=iam.grant.created` |
| PLAT-E72 | R50 | **smoke:** grower.in1 creates a case → read `case.case.events` | records `case.created` then `case.routed`, both key = caseId (same partition, in order) |
| PLAT-E73 | R51 | **IT:** a create that fails after the outbox insert (forced rollback) | no outbox row (so nothing to publish) |
| PLAT-E74 | R58 | **smoke:** admin.in revokes a grant of care.in → care.in calls `GET /cases` within 10 s | the revocation is effective (cache evicted), without waiting for the 5-minute TTL |

Debezium itself is checked in the **smoke** rows (E71, E72, E74) against the real local stack. ITs cover everything the services own (publication, roles, schemas, inbox, consumer), and **a connector-config test** checks `{iam,case}-outbox.json` (EventRouter fields, topic replacement, `publication.autocreate.mode=disabled`, table include list = outbox only). Running Kafka Connect inside every `mvn verify` costs too much memory for the local Docker VM.

## 3. API changes
None (no REST changes). New **event contracts** in `gc-backend/contracts/events/<eventType>.schema.json` + `README.md` (topics, keys, headers, compatibility rules).

## 4. Data & events
| Database | Change |
|---|---|
| gc_iam | V5: `iam.cdc_heartbeat`; `CREATE PUBLICATION gc_outbox FOR TABLE iam.outbox, iam.cdc_heartbeat WITH (publish = 'insert, update')`; grants for `${cdc_role}`. No inbox (IAM consumes nothing yet) |
| gc_case (common) | V5: schema `msg` with `msg.inbox` (PK consumer_group, event_id; index on processed_at) and `msg.cdc_heartbeat`; `CREATE PUBLICATION gc_outbox FOR TABLE msg.cdc_heartbeat WITH (publish = 'insert, update')` |
| gc_case (shard) | V5: `ALTER PUBLICATION gc_outbox ADD TABLE ${shard_schema}.outbox`; `GRANT SELECT` on it to `${cdc_role}` |

The app roles already have DML on the outbox via default privileges (only case-history tables revoke DELETE), so housekeeping needs no new grant.
| cluster | role `gc_cdc` (init script `local-dev/postgres/02-cdc-role.sh`; for an existing volume a one-off `local-dev/postgres/apply-cdc-role.sh`) |

**Topics** (auto-created locally by Connect with 3 partitions, RF 1; production counts per ADR-012 live in gc-infra): `iam.grant.events`, `case.case.events`, `dlq.case-service.iam-grant-cache`.

`docs/backend/01_RDS_SCHEMA.md` gains the publication, `msg.inbox` and the `gc_cdc` role.

## 5. Security
- `gc_cdc` sees outbox tables only (R52, R53). Events carry ids and codes, never PII (R54).
- Kafka is plaintext and unauthenticated **locally only**. Production uses MSK with IAM auth + TLS (gc-infra), and the consumer config takes the security settings from the environment.
- DLQ records keep the original value. The value has no PII by R54, so the DLQ does too.

## 6. Impact on existing specs
- SPEC-CASE-04 D7: maintenance jobs gain `outbox-cleanup` and `inbox-cleanup` (same per-shard pattern and R95 isolation).
- SPEC-IAM-01: grant changes now take effect in case-service within the event lag (R58), not the cache TTL.

## 7. Traceability
| Rule / Example | Test |
|---|---|
| R52, R53, R62 / E60–E62, E76 | `OutboxPublicationIT#platE60…`, `#cdcRoleSeesOutboxTablesOnly` (case-service, parameterized) · `IamOutboxPublicationIT#platE61…`, `#cdcRoleSeesOutboxOnly` |
| R54 / E63 | `CaseEventContractIT#everyOutboxRowMatchesItsContract` · `IamEventContractIT#everyOutboxRowMatchesItsContract` (parameterized over event types) |
| R55 / E64 · R61 / E70 | `EventHousekeepingIT#expiredRowsAreDeleted` (parameterized) |
| R56 / E65 · R57 / E66 · R58 / E67 · R59 / E68 · R60 / E69, E75 | `GrantCacheConsumerIT` (Testcontainers Kafka + Postgres + Redis): `#platE65…`, `#platE66…`, `#grantChangesEvictTheCachedPrincipal`, `#platE68…`, `#poisonRecordsGoToTheDlqAndThePartitionMovesOn` |
| R51 / E73 | `OutboxPublicationIT#outboxRowExistsOnlyIfTheTransactionCommits` (parameterized: commit / rollback) |
| R50, R52, R62 (connector config) | `DebeziumConnectorConfigTest` (parameterized over both connectors) |
| R64, R65 | `HexagonalRules` rows in every service's `ArchitectureTest` |
| R63 / E77 | local run (`run-services.sh start` on the existing volume) |
| R50 / E71, E72 · R58 / E74 | `local-dev/smoke.sh` (PLAT-E71, PLAT-E72, PLAT-E74a/b) |
