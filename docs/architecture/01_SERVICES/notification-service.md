# notification-service
**Context:** Messaging · **Phase:** 1 (email/SMS/WhatsApp), 2 (preferences, batching, failover) · **Tech:** Spring Boot, Kafka consumers, Postgres (partitioned), Redis

## Responsibilities
- Turn domain events into messages per **country profile**: channels, templates, languages, quiet hours, consent (NOT-1..3).
- **Batching** per case (≤ 1 message per case per 5 min) to control SMS/WhatsApp cost at 2B comments/day.
- Provider routing & **failover** (e.g., WhatsApp → SMS), per-provider rate limits, retries with backoff, DLQ.
- Delivery receipts (provider webhooks, signature-verified).
- Regulatory/transactional messages bypass marketing opt-outs.

## Component diagram
```mermaid
flowchart LR
  k[(Kafka case.*, comment.*,<br/>claims.*, iam.otp.*)] --> c[Event consumers<br/>idempotent inbox]
  c --> pol[Policy<br/>consent, quiet hours, batching window]
  pol --> rend[Template renderer<br/>ICU, per language]
  rend --> q[(Kafka notify.dispatch<br/>per channel)]
  q --> d1[SMS dispatcher]
  q --> d2[WhatsApp dispatcher]
  q --> d3[Push dispatcher]
  q --> d4[Email dispatcher - SES]
  d1 --> p1[SMS providers A/B]
  d2 --> p2[WhatsApp BSP]
  d3 --> p3[FCM / APNs]
  p1 -->|webhook| wh[Receipt webhooks] --> db[(Postgres deliveries<br/>monthly partitions)]
  pol <--> redis[(Redis<br/>batch windows, provider rate limits)]
```

## Key flow — comment notification with batching
```mermaid
sequenceDiagram
  participant K as Kafka
  participant N as notification-service
  participant R as Redis
  participant W as WhatsApp BSP
  K->>N: comment.posted (case 123, PUBLIC, author=agronomist)
  N->>R: SET NX batch:case:123 (TTL 5m)
  alt window already open
    N->>R: RPUSH pending:case:123 commentId
  else first in window
    N->>N: schedule flush at +5m
  end
  N->>N: flush: render "2 new messages on case 71-0318-27-482139-6"
  N->>W: send template (grower language)
  W-->>N: accepted id
  W-->>N: webhook delivered
```

## API
`GET/PUT /preferences` (grower/staff), `POST /webhooks/{provider}` (signed), admin template management (versioned, maker-checker).

## Data
`templates` (versioned per country/language), `preferences`, `deliveries` (monthly partitions, 90-day retention), `inbox`.

## Events
- **Consumes:** most domain events.
- **Publishes:** `notification.delivered`, `notification.failed`.

## Scaling profile
- Bursty: follows comment volume (up to ~4.6K/s per country), reduced by batching.
- Dispatchers scale on Kafka lag (KEDA).
- **The real limits are provider throughput and cost.**

## Limitations
- WhatsApp templates need provider pre-approval per language. New templates have lead time.
- Exactly-once delivery to phones isn't possible. At-least-once plus dedup by `(event_id, channel)` before send minimises duplicates.

## Trade-offs
| Choice | Alternative | Why |
|---|---|---|
| Own service + multiple providers | Single CPaaS (e.g., one provider for all) | Cost and resilience per country. Costs: more integrations. |
| Batching window | Notify per comment | ~5–10× fewer messages. Costs: up to 5 min delay (regulatory/urgent messages skip batching). |
