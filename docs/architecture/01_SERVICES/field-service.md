# field-service
**Context:** Field force · **Phase:** 2 (spray marketplace module: phase 3) · **Tech:** Spring Boot, Postgres + PostGIS (partitioned by country/territory), Redis, Kafka

## Responsibilities
- Agronomist and territory-manager profiles: skills (crops, BU), zones (polygons), calendars, leave.
- Visit scheduling for cases (CASE-5), day plans, check-in with geo-verification.
- **Offline sync API** for the field PWA (FLD-1): command log, idempotent replay, conflict rules.
- Demo plots & field events (FLD-2), retailer visits (FLD-3).
- Training/certification records (STW-3).
- Phase 3: spray-service marketplace (SPR-1..4): operator eligibility, capacity, weather windows, no double booking.

## Component diagram
```mermaid
flowchart LR
  pwa[field-pwa<br/>offline command queue] -->|sync batch| sync[Sync endpoint<br/>idempotent commands]
  api[REST API] --> app[Application layer]
  sync --> app
  app --> sched[Scheduler<br/>capacity, conflicts, travel]
  app --> visit[Visit aggregate<br/>check-in geofence, forms]
  app --> demo[Demo plots and events]
  app --> spray[Spray marketplace<br/>phase 3]
  sched --> db[(Postgres + PostGIS<br/>partitioned by country)]
  visit --> db
  demo --> db
  spray --> db
  app --> ob[Outbox] --> db
  db -. Debezium .-> k[(Kafka field.*)]
  k -->|case.assigned| app
```

## Key flow — offline visit sync
```mermaid
sequenceDiagram
  participant P as field-pwa (offline 6h)
  participant F as field-service
  participant DB as Postgres
  participant K as Kafka
  P->>F: POST /sync {commands: [checkIn c1, addObservation c2, complete c3]}
  loop each command in order
    F->>DB: INSERT processed_commands(command_id) ON CONFLICT DO NOTHING
    alt already processed
      F-->>F: skip (idempotent replay)
    else new
      F->>F: validate transition and geofence (server rules win)
      F->>DB: apply + outbox field.visit.*
    end
  end
  F-->>P: per-command results + server state snapshot
  DB-->>K: field.visit.completed -> case-service updates the case
```

## API (selected)
`GET /me/day-plan`, `POST /visits`, `POST /visits/{id}/check-in`, `POST /sync`, `GET /agronomists/available?zone=&date=&skill=`, `POST /demos`, `POST /spray-jobs` (phase 3).

## Data
`agronomists`, `zones` (geometry), `calendars`, `visits`, `visit_forms`, `processed_commands`, `demos`, `spray_operators`, `spray_jobs`, `outbox`.

## Events
- **Publishes:** `field.visit.scheduled`, `field.visit.checked_in`, `field.visit.completed`, `field.demo.*`, `spray.job.*`.
- **Consumes:** `case.assigned`, `case.cancelled`, `iam.user.deactivated`.

## Scaling profile
- ~15K agronomists per large country: low request rate but bursty syncs (morning and evening).
- Sync payloads include photos by reference only (media-service).

## Limitations
- Offline conflicts: server rules win for state transitions. Field notes merge (append-only), so a few user-visible "your change was superseded" cases are unavoidable.
- Route optimisation is out of scope (an ordered list is shown). Real VRP optimisation would be a separate engine later.

## Trade-offs
| Choice | Alternative | Why |
|---|---|---|
| Command-log sync with idempotent IDs | CRDT/automerge document sync | Simpler, auditable and fits state machines. Less elegant for free-text co-editing, which isn't needed here. |
| Spray marketplace as a module here | Separate service | Shares scheduling/capacity logic. Split out if it becomes its own business line. |
