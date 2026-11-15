---
name: event-contract
description: Define, change or consume a GrowerCare Kafka event (Avro schema in gc-contracts, topic naming, keys, envelope headers, outbox publishing, idempotent inbox consumption, retries/DLQ). Use whenever a service publishes or consumes a domain event or a CDC topic.
---

# Event contracts (Kafka)

Background: `docs/architecture/03_TECH_STACK.md §3`, ADR-012, `02_DESIGN_PATTERNS.md` (outbox, inbox, sagas).

## 1. Define the event in `gc-contracts`
- Path: `gc-contracts/events/<domain>/<EventName>.avsc`, namespace `com.growercare.events.<domain>`.
- Topic: `<domain>.<entity>.<event>` (e.g., `case.case.status_changed`). One event type per topic, unless a set of events must share ordering for an aggregate. In that case use `<domain>.<entity>.events` with a union and document why.
- Key: the aggregate ID as a **string** (`case_id`), never random.
- Required fields: business IDs as strings, `country`, minimal payload (no full aggregates, no PII unless essential; attachments by S3 key = claim check).
- Envelope (Kafka headers, added by the outbox router): `event_id` (UUIDv7), `event_type`, `occurred_at`, `cell`, `country`, `trace_id`, `schema_version`.
- Compatibility: **BACKWARD**. Allowed: add an optional field with a default. Forbidden: remove/rename fields, change types. Breaking change = new event name/version topic + dual publish + consumer migration.

## 2. Publish (outbox only)
```java
@Transactional
public CaseId changeStatus(ChangeStatusCommand cmd) {
    var c = cases.load(cmd.caseId());
    c.transitionTo(cmd.status(), cmd.actor());      // domain rule
    cases.save(c);                                   // optimistic lock
    outbox.append(CaseStatusChanged.from(c));         // same transaction
    return c.id();
}
```
Never call `KafkaTemplate` from business code. Debezium's Outbox Event Router publishes the event.

## 3. Consume (idempotent)
- Extend `IdempotentConsumer<T>`: it inserts `event_id` into `inbox` in the same transaction as the side effect, and skips duplicates.
- Commit offsets after processing. Retry topics with backoff (e.g., 1 min, 10 min, 1 h), then `dlq.<consumer-group>`.
- Expect out-of-order events across aggregates; within an aggregate, order is guaranteed by key.
- Consumers must tolerate **unknown fields** and **unknown enum values** (forward compatibility).

## 4. Checklist
- [ ] Schema added in `gc-contracts`, compatibility check green
- [ ] Topic + partitions declared in `gc-infra` (Terraform MSK topic module) using ADR-012 counts
- [ ] Producer test: use case writes the outbox row (Testcontainers Postgres)
- [ ] Consumer test: duplicate delivery processed once; poison message goes to DLQ (Testcontainers Kafka)
- [ ] Consumer listed in the service doc's "Events" section
