# Case Comments Design — Multi-Actor Conversation at Scale
### GrowerCare · Design doc v0.1 · 2026-11-15
*Companion to `04_ID_GENERATION_DESIGN.md` (v0.2, DB-generated sharded IDs) and `03_DATA_PLATFORM_DESIGN.md`.*

**Confirmed volume (2026-11-15):** worst case is **1 case per user per day** (10M users per country, 20 countries). Each case gets comments from **multiple actors**: grower, care agent, agronomist, retailer, quality, finance.

---

## 1. Volume

| | Typical (5 comments/case) | Worst (10 comments/case) |
|---|---|---|
| Comments per day per country | 50M | **100M** |
| Average rate per country | ~580/s | ~1,160/s |
| Daytime peak per country (≈ 4× avg) | ~2,300/s | **~4,600/s** |
| Peak per logical shard (÷ 64) | ~36/s | ~72/s |
| Global average (20 countries) | ~11.6K/s | ~23K/s |
| Storage per country (≈ 0.5 KB incl. indexes) | ~25 GB/day | ~50 GB/day |
| Hot comments per country (≈ 100-day window) | ~2.5 TB | ~5 TB |

**Conclusion:** comments, not cases, are the largest write stream: 5–10× the case volume. They drive Postgres, CDC, Kafka and Snowflake sizing.

ID capacity is not a problem: each shard's `next_id()` can issue 1,024 IDs per ms, and the worst-case need is ~72/s per shard.

---

## 2. Data placement: comments live with their case

- A comment is stored in **the same shard as its case**, whoever writes it. An agent in a call center or a retailer posts into the grower's shard.
- Routing needs no lookup: `POST /cases/{case_id}/comments` → decode country + shard from `case_id` → cluster + schema.
- `comment_id` comes from the same shard's `next_id()`, so it also encodes country + shard + time, and it is **time-ordered within the case**.

```sql
CREATE TABLE in_s27.case_comments (
  comment_id     bigint      PRIMARY KEY DEFAULT in_s27.next_id(),
  case_id        bigint      NOT NULL REFERENCES in_s27.cases(case_id),
  author_id      bigint      NOT NULL,
  author_role    text        NOT NULL,     -- GROWER, CARE_AGENT, FIELD_AGRONOMIST, RETAILER_USER, …
  visibility     text        NOT NULL,     -- PUBLIC (grower-visible) | INTERNAL | PARTNER
  body           text        NOT NULL CHECK (length(body) <= 4000),
  lang           text,                     -- detected/declared language
  attachments    jsonb,                    -- S3 object keys + metadata only
  client_msg_id  uuid        NOT NULL,     -- UUIDv7 from the UI/app for idempotent retries
  reply_to_id    bigint,                   -- optional threading (one level)
  edited_at      timestamptz,
  deleted_at     timestamptz,              -- soft delete; body retained in audit
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (case_id, client_msg_id)
);
CREATE INDEX ON in_s27.case_comments (case_id, comment_id DESC);
```

**Partitioning:** `case_comments`, `case_activity` and `cases` are **range-partitioned on `case_id`**. Because `case_id` starts with its creation time, one partition = one week of case creation, and a case and all its comments land in the same week partition. This matters for purging (§6).

---

## 3. Who can write and see what

| Actor | Can post | Sees | Rules |
|---|---|---|---|
| Grower | PUBLIC | PUBLIC | Only on own cases; not after case is CLOSED (+ reopen window) |
| Care agent | PUBLIC, INTERNAL | All | Within grant scope (country/territory/BU) |
| Field agronomist | PUBLIC, INTERNAL | All | Assigned cases only |
| Retailer / distributor | PARTNER (and PUBLIC if grower consented) | PUBLIC + PARTNER | Only if the grower shared the case with them (ACC-12) |
| Quality / Finance | INTERNAL | All | Case type & BU scope |
| Auditor | — | All, read-only | Time-boxed grant |

- Visibility is enforced in the **query** (`WHERE visibility IN (:allowed)`), not filtered in the UI.
- PII in INTERNAL notes follows the masking rules (ACC-3).
- Edits are allowed for 15 minutes, with the previous body kept in the audit trail. Deletes are soft. INTERNAL notes on regulated case types (adverse incidents) **cannot be deleted**.

---

## 4. UI behaviour

| Feature | Design |
|---|---|
| Comment feed | Newest-first, **keyset pagination** on `comment_id` (`?before=<comment_id>&limit=30`); never OFFSET |
| Tabs / filters | "Conversation" (PUBLIC), "Internal notes", "Partner"; filter by actor role; the "older than 3 months" switch routes to Snowflake as in doc 03 |
| Posting | **Optimistic UI**: show the message immediately with a client `client_msg_id` (UUIDv7); retries are safe because of `UNIQUE(case_id, client_msg_id)`; on conflict the server returns the existing comment |
| Live updates | When several actors have the case open: **SSE/WebSocket** channel per case (`case:{case_id}`), fed from the outbox → Kafka → realtime gateway. Fallback: poll `?after=<last comment_id>` every 15 s |
| Unread markers | `case_read_cursor(case_id, user_id, last_read_comment_id)`. Unread = comments with `comment_id > last_read`. Cheap because IDs are ordered. |
| Attachments | Presigned S3 upload first (staging key), then the comment references the object key; virus scan before others can view |
| Language | Original text always shown; optional "Translate" for staff, labelled as machine translation |
| Mentions | `@agronomist` triggers a notification, scoped to people who can see that visibility level |
| Rate limit | Per user: 20 comments/min, 300/day for growers; higher for staff. Duplicate-text detection. |
| Accessibility | Screen-reader live region for new messages; large touch targets for the field app |

**Notifications:** a new PUBLIC comment from staff notifies the grower (push / WhatsApp / SMS per country profile). Notifications are batched (e.g., at most one per case per 5 min) to control SMS cost at this volume.

---

## 5. Keeping comments from hurting the case row

**Don't update the `cases` row on every comment** (no `comment_count++`, no `last_comment_at`, no `version` bump):
- **Optimistic locking:** if comments bumped `version`, a grower's comment would make an agent's simultaneous status change fail with 409 for no reason.
- **CDC doubling:** each comment would also produce a `cases` update event: +100M extra CDC events per country per day.
- **Vacuum/WAL:** constant rewrites of wide case rows.

**Instead:**
- The feed reads `case_comments` directly. The index `(case_id, comment_id DESC)` makes "latest N" a single index range scan.
- List screens that need "last activity" / "comment count" read a small `case_comment_stats(case_id, count, last_comment_id, last_comment_at)` table. It is updated in the same transaction with a single-row upsert; it's narrow, isn't CDC'd to Snowflake (Snowflake recomputes it), and isn't part of optimistic locking.

---

## 6. Impact on archive & purge (update to doc 03)

At the confirmed volume, the doc-03 approach of **row-by-row deletes** of archived cases + comments means ~150M deletes per country per day. Each delete is a CDC event and creates vacuum load. That's not sustainable.

**Revised approach: purge by partition, using the time inside `case_id`.**
1. `cases`, `case_comments`, `case_activity` are range-partitioned by `case_id` → **one partition per case-creation week**.
2. When a week partition is ≥ 13 weeks old, most of its cases have been closed for > 90 days. The few that are still open or recently closed (**stragglers**, expected ≈ 1–3%) are **copied into a `*_carryover` table set** (same schema, same IDs) by the archiver, and their routing flag is updated.
3. The whole partition is **verified** against Snowflake (count + hash digest per partition, same canonical hash as doc 03 §6).
4. `DETACH PARTITION … CONCURRENTLY` → `DROP`. This produces **no row-level CDC delete events** and no vacuum.
5. Carryover rows go through the original row-level purge-marker protocol later. That is only a small fraction, so row deletes are affordable.

The hot window still means "90 days after closure" for every case: stragglers stay hot in the carryover tables until eligible.

---

## 7. Pipeline sizing impact (worst case)

| Stream | Per country / day | Global / day |
|---|---|---|
| Case inserts + updates (≈ 4 changes/case) | ~40M | ~800M |
| Comments | ~100M | ~2B |
| Activity events (≈ 6/case) | ~60M | ~1.2B |
| **Total CDC events** | **~200M (~2.3K/s avg, ~10K/s peak)** | **~4B** |

- **Kafka:** per-country topics, `case_comments` topic with 48–64 partitions keyed by `case_id`, so all comments of a case stay in order.
- **Snowflake:** comments are the biggest RAW table. Cluster `CURATED.CASE_COMMENTS` on `(country, case_created_day)`; RAW retention 1 year (as approved).

---

## 8. Requirements (add to BRD §6.3)

| ID | Requirement | Pri |
|---|---|---|
| CMT-1 | Comments stored in the case's shard; `comment_id` from the shard's `next_id()` | M |
| CMT-2 | Visibility levels PUBLIC / INTERNAL / PARTNER, enforced in queries per actor role and grant scope | M |
| CMT-3 | Idempotent posting via `client_msg_id` (UUIDv7) with `UNIQUE(case_id, client_msg_id)` | M |
| CMT-4 | Keyset pagination on `comment_id`; no OFFSET | M |
| CMT-5 | Posting a comment doesn't update the `cases` row or its `version` | M |
| CMT-6 | Live updates via SSE/WebSocket per case, with polling fallback | P2 |
| CMT-7 | Unread tracking via per-user read cursors | P2 |
| CMT-8 | 15-minute edit window with history; soft delete; INTERNAL notes on regulated case types are immutable | M |
| CMT-9 | Per-user rate limits and duplicate detection | M |
| CMT-10 | Attachments via presigned upload + virus scan before display | P2 |
| CMT-11 | Notification batching per case to control SMS/WhatsApp cost | P2 |
| CMT-12 | Comments older than the hot window are served from Snowflake (doc 03 routing) | P2 |
