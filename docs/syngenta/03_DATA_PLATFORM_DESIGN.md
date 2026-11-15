# Data Platform Design — Hot/Cold Split with Kafka, Debezium & Snowflake
### GrowerCare · Design doc v0.1 · 2026-11-15
*Companion to `02_BUSINESS_REQUIREMENTS.md` (requirements DATA-1 … DATA-14 are defined in §11 here and referenced from the BRD).*

---

## 1. Requirement (as stated)

1. **Kafka + Debezium** stream every change from the OLTP database (**Amazon RDS for PostgreSQL**) to **Snowflake**.
2. **RDS holds the latest data** (hot window = **3 months**).
3. Queries for data **older than 3 months** are served from **Snowflake curated (materialized) tables**.
4. The sink must be **100% accurate**: no lost changes, no duplicates, no stale versions.
5. After data is **verified** in Snowflake, it is **hard-deleted from Postgres**.

## 2. What "100% accurate" means here (contract)

Exactly-once delivery across systems isn't guaranteed by Kafka or Debezium on their own. Correctness is therefore defined on the **end state**, and proven before any delete:

| Property | Definition | How it's guaranteed |
|---|---|---|
| **Completeness** | Every committed row version in RDS reaches Snowflake | Logical replication slot (WAL is kept until acknowledged), Kafka `acks=all`, RF=3, `min.insync.replicas=2`; lag alerts |
| **No duplicates** | Each primary key appears once in curated tables | Idempotent `MERGE` keyed by primary key |
| **Latest version wins** | Curated row = the latest committed version in RDS | Each event carries the source **LSN**; `MERGE` applies only if `incoming.lsn > target.lsn`; `@Version` column cross-checked |
| **Verified before purge** | No row is deleted from RDS unless Snowflake holds an identical copy | Row-by-row hash reconciliation per archive batch (§6) |
| **Purge is not a business delete** | Archiving deletes don't remove data from Snowflake | Purge-marker protocol (§5) |
| **Freshness** | Snowflake ≤ 5 min behind RDS at p99 | Snowpipe Streaming + task schedule; alert at 10 min |

If any property can't be proven for a row, **that row stays in RDS**. Failing safe means keeping data longer, never losing it.

---

## 3. Architecture (per regional cell)

```
 ┌──────────── RDS PostgreSQL 16 (Multi-AZ) ────────────┐
 │  hot tables (≤ 3 months of mutable data)              │
 │  outbox table (domain events)                         │
 │  archived_record_index (thin stubs, no PII)           │
 │  logical replication: publication `cdc_pub`           │
 └───────┬───────────────────────────────┬──────────────┘
         │ slot: cdc_tables               │ slot: cdc_outbox
         ▼                                ▼
  Debezium PG connector #1         Debezium PG connector #2 (Outbox Event Router)
  (table CDC → Snowflake)          (domain events → notifications, search, ERP, alerts)
         │  Avro + Schema Registry (BACKWARD compatible)
         ▼
 ┌─────────────── Amazon MSK (Kafka), 3 brokers, RF=3 ───────────────┐
 │ cdc.<cell>.public.cases  cdc.<cell>.public.case_comments  ...      │  key = primary key
 │ dbz.heartbeat · dbz.signals · dlq.snowflake                        │
 └─────────┬──────────────────────────────────────────────────────────┘
           ▼
  Snowflake Kafka Connector (Snowpipe Streaming, offset tokens)
           ▼
 ┌─────────────────────── Snowflake (same cloud region as the cell) ─────────────────────┐
 │ RAW      : append-only change log per table (op, before, after, lsn, tx_id, ts_ms)     │
 │ CURATED  : current-state tables via Stream + Task MERGE (LSN-ordered, purge-aware)     │
 │ SERVING  : Dynamic Tables / materialized views shaped for archive queries              │
 │            + row access policies (region/country/territory/BU) + masking policies      │
 └───────────────────────────────────────────────────────────────────────────────────────┘
           ▲                                                  │
           │ verify (hash reconcile)                          │ archive queries
  ┌────────┴─────────── Archiver service (Spring Boot job) ───┴──────────┐
  │ select eligible → verify → mark PURGING → confirm → hard delete        │
  └───────────────────────────────────────────────────────────────────────┘
  Query Router (in API): hot → RDS · older / spanning → Snowflake SERVING
```

**Why two Debezium connectors:** the outbox stream feeds user-facing side effects (notifications). Table CDC feeds analytics and archive. Separate slots keep a Snowflake backlog from delaying an SMS.

---

## 4. What is archived (and what is never archived)

"Older than 3 months" must be defined by **business state**, not `created_at`. A complaint opened 5 months ago can still be under lab analysis.

| Data | Type | Eligibility rule | Purge mechanism |
|---|---|---|---|
| Cases + children (comments, evidence metadata, assignments, approvals) | Mutable aggregate | `status ∈ terminal` AND `closed_at < now − 90d` AND no legal hold AND no open linked case AND no pending regulatory report AND not reopened | Purge-marker protocol, batched by aggregate |
| Activity / audit events | Append-only | `created_at < now − 90d` (event time) | **Monthly partitions** → `DETACH` + `DROP` after verification (emits no row-level deletes) |
| Authenticity scan events | Append-only, high volume | `created_at < now − 90d` | Monthly partitions, detach/drop |
| Notification delivery log | Append-only | `created_at < now − 90d` | Monthly partitions |
| **Master/reference data** (users, growers, farms, partners, products, registrations, country profiles) | Master | **Never purged** (needed for joins, auth, uniqueness) | — |
| Outbox | Transient | Delete after publish + 7 days | Plain delete; outbox table excluded from Snowflake CDC |

Each table's hot window is configurable (default 90 days). A country can extend it for its own data, never shorten it.

### Stub index kept in RDS (no PII)
```sql
archived_record_index(
  entity_type, entity_id PK, case_no UNIQUE, country, territory_id, bu,
  partner_org_id, closed_at, archived_at, archive_batch_id, row_hash)
```
- Deep links like `/cases/{id}` still resolve: the router sees the stub and fetches from Snowflake.
- The permission check runs **before** touching Snowflake, using the scope columns.
- Unique business numbers (`case_no`) stay unique forever.

---

> **Update 2026-11-15:** at the confirmed volume (10M cases + up to 100M comments per country per day), row-by-row purging is too heavy for the main path. `cases`, `case_comments` and `case_activity` are now range-partitioned by `case_id` (creation week) and purged by **detach + drop** after verification. Still-open cases are moved to carryover tables first. The protocol below now applies only to those carryover rows. See `05_CASE_COMMENTS_DESIGN.md` §6.

## 5. The purge-marker protocol (avoids the Debezium-delete trap)

**The trap:** Debezium captures the archiver's `DELETE` like any other delete. If the sink applies it, Snowflake loses the very data we archived.

**The fix:** mark first, delete second, and teach the `MERGE` to ignore deletes of marked rows.

```
Archiver (per batch of ≤ 1,000 aggregates)
 1. SELECT eligible ids (FOR UPDATE SKIP LOCKED)            ── RDS
 2. VERIFY: row-by-row hash RDS vs Snowflake CURATED (§6)   ── must match 100%
 3. UPDATE … SET archive_state='PURGING', archive_batch_id=B  ── RDS (this is CDC'd)
 4. WAIT until Snowflake CURATED shows archive_state='PURGING' for ALL ids in B
       (proves the pipeline has caught up past step 3 for these keys)
 5. RE-VERIFY hashes (excluding archive_state columns), then in ONE transaction:
       INSERT archived_record_index stubs
       DELETE children, then parents        ── RDS (CDC emits op='d')
 6. Snowflake MERGE sees op='d' AND target.archive_state='PURGING'
       → keep the row, set is_in_oltp=false, oltp_purged_at=ts
    Any other op='d' (real business delete, e.g. privacy erasure) → applied as a delete
 7. Record batch B: counts, hash digest, timings → archive_audit (both sides)
```

**Ordering guarantee:** Kafka key = primary key, so all events for one row are in one partition, in commit order. Step 3's update always arrives before step 5's delete. Step 4 makes this explicit rather than assumed.

**Concurrency:** if a user edits or reopens the case between steps 1 and 5, `@Version` changes. The delete is written as `DELETE … WHERE id=? AND version=?`, and a mismatch aborts that aggregate, clears the marker and leaves the row hot.

**Append-only partitions:** verify the whole partition (count + hash digest per day), then `ALTER TABLE … DETACH PARTITION … CONCURRENTLY` and `DROP TABLE`. No row-level CDC events, no vacuum load. Partitions are created ahead of time by `pg_partman`.

---

## 6. Verification (reconciliation)

| Level | When | What |
|---|---|---|
| **Per archive batch** (gate for purge) | Steps 2 & 5 | For every id: canonical row hash computed by **the same Java function** on the RDS row and the Snowflake row (avoids cross-database hash and format differences). Compare the full row set, children included. Any mismatch → batch quarantined, alert. |
| **Daily continuous** | 02:00 per cell | Per table, per day bucket: row count + order-independent digest, RDS vs CURATED (hot window). Drift → incident + targeted re-snapshot. |
| **Weekly cold check** | Weekly | Snowflake CURATED vs `archived_record_index` stubs: every stub has exactly one curated row with matching `row_hash`. |

**Canonical hash** = SHA-256 over the row's columns in a fixed order:
- timestamps normalized to UTC microseconds
- decimals at fixed scale
- `NULL` represented by a sentinel
- text in NFC Unicode form
- excluded: `archive_state`, `archive_batch_id`, and the CDC metadata columns

`row_hash` is stored in the stub and in Snowflake for future audits.

---

## 7. Snowflake layers

| Layer | Object | Built by | Notes |
|---|---|---|---|
| RAW | `RAW.CASES_CHANGES` … | Kafka connector (Snowpipe Streaming) | Append-only. Keeps `op`, `before`, `after`, `lsn`, `tx_id`, `source_ts`, kafka offset. Retention: 1 year, then summarized. |
| CURATED | `CURATED.CASES` … current state | **Stream + Task** running `MERGE` every 1 min | Rules: dedupe within the micro-batch by PK keeping max LSN; `WHEN MATCHED AND src.lsn <= tgt.lsn THEN skip`; purge-aware delete rule (§5) |
| SERVING | `SERVING.CASE_ARCHIVE` (case + latest status + product + territory + counts), `SERVING.ACTIVITY_ARCHIVE`, KPI tables | **Dynamic Tables** (`TARGET_LAG = '10 minutes'`) | Denormalized for the archive screens. Snowflake materialized views can't contain joins; Dynamic Tables can. Clustered by `(country, closed_at)`. |
| SHARED | Anonymized aggregates | Secure views + data sharing | Only these leave the cell (global dashboards) |

**Access control in Snowflake mirrors the platform's access model:**
- **Row access policies** on SERVING filter by region, country, territory, BU and partner org, using a mapping table synced from the platform's grants.
- **Masking policies** on PII columns (phone, name, precise geolocation).
- End users never connect to Snowflake directly. The API calls Snowflake with a service role and passes the caller's scope (`SET` session variables, or secure UDF parameters). This is defense-in-depth, not the only check.

---

## 8. Query routing (API behaviour)

| Request | Routed to | Why |
|---|---|---|
| List/search where the entire date range is within the hot window | **RDS** | Fresh, cheap, fast |
| List/search where any part of the range is older than the hot window | **Snowflake SERVING** for the **whole** range | Snowflake has *all* data, hot and cold, so no cross-store merge or paging bugs. Freshness lag (≤ 10 min) shown in UI. |
| `GET /cases/{id}` | RDS → if absent, check `archived_record_index` → Snowflake | Deep links keep working |
| Activity of an archived case | Snowflake | |
| Reopen / edit an archived case | **Not allowed directly**. "Restore" action (admin, audited) rehydrates the aggregate into RDS from Snowflake, with hash check. | Archive is read-only |

**API contract:** `GET /api/v1/cases?from=…&to=…` returns `X-Data-Source: oltp|archive` and `X-Data-Freshness-Seconds`. Archive queries:
- cursor-paginated, max 200 rows per page
- 10 s timeout
- per-user rate limit
- results cached 5 min
- run on a dedicated XS–S warehouse (auto-suspend 60 s), separate from analytics warehouses

**UI:** "Recent" (default) and "Archive" tabs. Archive search requires a date range and at least one filter, to protect cost.

---

## 9. Operating the pipeline (where it really fails in production)

| Risk | Mitigation |
|---|---|
| **Replication slot lag fills RDS disk** (connector down → WAL retained) | `max_slot_wal_keep_size` cap, CloudWatch alarm on `OldestReplicationSlotLag` and free storage, Debezium `heartbeat.action.query` so idle tables still advance the slot, runbook |
| **RDS failover loses the logical slot** (depends on engine version and failover-slot support) | Verify for the chosen RDS version. Otherwise auto-recreate the slot and run a Debezium **incremental snapshot** (signal table) for the gap window; daily reconciliation catches residue |
| **Schema change breaks the sink** | Flyway migrations must be additive (expand → migrate → contract); Schema Registry BACKWARD compatibility enforced in CI; Snowflake connector schema evolution on RAW; CURATED changes via versioned SQL migrations (schemachange) |
| Poison / unparseable message | Connector DLQ topic, alert, replay tool |
| **Sink down longer than Kafka retention** | Topic retention 7 days (PII limits), alert at 24 h of lag; recovery by incremental snapshot |
| Large backfill / initial load | Debezium incremental snapshot (chunked, non-blocking) |
| TOAST / unchanged large columns arrive as placeholders | `REPLICA IDENTITY FULL` only on small tables; for large-text tables, `MERGE` keeps the existing value when it receives the "unavailable value" placeholder |
| Delete events with only the PK | Handled by design: the purge rule checks the **target** row's `archive_state`, not the event's before-image |
| Cost runaway in Snowflake | Separate warehouses (ingest/merge, archive-serve, analytics), resource monitors, auto-suspend, clustering on query keys |

**Observability:**
- end-to-end lag: commit timestamp → CURATED merge timestamp
- per-table event rates
- reconciliation pass/fail
- purge throughput
- quarantined batches
- slot lag

All per cell, with SLO alerts.

---

## 10. Privacy & residency interactions

- **One Snowflake account per regional cell, in the same cloud region**. Personal data never crosses regions; only anonymized aggregates are shared to the global account.
- **China cell:** Snowflake doesn't, to my knowledge, offer mainland-China regions. The China cell needs a local warehouse (decision needed; see §12).
- **Privacy erasure (DSAR):** propagates as a *business* delete (or field nulling) through CDC → applied in CURATED and SERVING. A RAW-layer scrubber job removes the subject's history. Snowflake **Time Travel** (set to 1 day on PII tables) and **Fail-safe** (7 days, not configurable) mean physical erasure completes within ~8 days; document this in the privacy notice. Kafka topic retention (7 days) bounds the stream copy.
- **Legal hold:** blocks purge eligibility in RDS and blocks erasure in Snowflake for held records.

---

## 11. Requirements added to the BRD

| ID | Requirement | Pri |
|---|---|---|
| DATA-1 | Every change to in-scope tables is streamed RDS → Kafka (Debezium) → Snowflake RAW with ≤ 5 min p99 end-to-end lag | M |
| DATA-2 | CURATED current-state tables are built with idempotent, LSN-ordered `MERGE`; duplicates and out-of-order events don't change the result | M |
| DATA-3 | Outbox events published by a separate Debezium connector/slot (Outbox Event Router) | M |
| DATA-4 | Hot window: mutable aggregates eligible 90 days after reaching terminal state; append-only tables by event time; configurable per table/country (extend only) | P2 |
| DATA-5 | Purge only after row-by-row hash verification and the purge-marker protocol; mismatches quarantine the batch | P2 |
| DATA-6 | Append-only tables partitioned monthly; purge by detach + drop after partition-level verification | P2 |
| DATA-7 | `archived_record_index` stubs keep IDs, case numbers and scope attributes resolvable after purge | P2 |
| DATA-8 | Query router: hot-only ranges → RDS; any range touching cold data → Snowflake SERVING for the whole range; response states source & freshness | P2 |
| DATA-9 | Archive is read-only; audited admin "restore" rehydrates an aggregate into RDS | P3 |
| DATA-10 | Snowflake row access & masking policies mirror platform ABAC scopes; grants synced from the platform | P2 |
| DATA-11 | Daily RDS↔Snowflake reconciliation (counts + digests) and weekly cold-store audit; failures raise incidents | P2 |
| DATA-12 | Schema changes: additive migrations, Schema Registry compatibility check in CI, versioned Snowflake migrations | M |
| DATA-13 | Pipeline SLOs and alerts: slot lag, consumer lag, merge lag, DLQ size, reconciliation status | M |
| DATA-14 | DSAR erasure propagates to CURATED, SERVING and RAW; erasure completion accounts for Time Travel/Fail-safe windows | P2 |

### Capacity sanity check (largest cell)
| Item | Estimate |
|---|---|
| OLTP writes at peak | ~150–250/s |
| CDC events (incl. child rows, activity) | ~600–1,000/s peak — small for a 3-broker MSK cluster |
| Kafka topic partitions | 12 per high-volume table topic, 3 for others |
| RDS hot data | ~3–5 months of cases + children ≈ low hundreds of GB (fits one `db.r7g.2xlarge`-class primary + 1 read replica) |
| Snowflake growth | ~1–2 TB/year compressed per large cell, RAW being the largest part |

---

## 12. Decisions (confirmed 2026-11-15)
1. **Hot-window rule:** 90 days after case **closure**. ✅
2. **Archive consumers:** both. App users query older data through UI filters (date range + filters → API → Snowflake SERVING); analysts get direct, policy-scoped Snowflake roles. ✅
3. **China:** out of scope for now. ✅
4. **RAW change-log retention:** 1 year. ✅
