# control-plane-service
**Context:** Global configuration (no personal data) · **Phase:** 1 · **Tech:** Spring Boot, Postgres (global, multi-region read replicas), S3 config bundles, Kafka (global → cells via MirrorMaker 2 or bundle pull)

## Responsibilities
- **Country profiles** (CFG-1..3): locale, channels, case types enabled, workflow variants, regulatory clocks, tax/payment rails, privacy settings, seasons. Versioned, effective-dated, **two-person approval**.
- **Country registry** (ID-5): country index (6-bit), public CC slot, region, reference timezone, extra indices for growth.
- **Shard map** (ID-6): `(country, shard) → cluster endpoint`, versioned, used by `shard-routing` in every service.
- **Compliance-pack versions** enabled per country.
- **Feature flags** (per cell/country/role), maintenance banners.
- Workflow definitions per case type × country, validated against HQ guardrails.

## Component diagram
```mermaid
flowchart LR
  admin[Regional/Global admins<br/>staff console] --> api[Admin API]
  api --> appr[Approval workflow<br/>maker-checker, diff view]
  appr --> db[(Postgres global config<br/>versioned rows)]
  db --> pub[Bundle publisher]
  pub --> s3[(S3 signed config bundles<br/>per cell)]
  pub -. event .-> k[(Kafka control.*)]
  s3 --> cells[Cells: services load bundle<br/>at start + on event]
  cells --> cache[(Local in-memory + Redis)]
```

## Key flow — publish a country profile change
```mermaid
sequenceDiagram
  participant RA as Regulatory affairs (maker)
  participant RH as Regional admin (checker)
  participant CP as control-plane-service
  participant S3 as S3 bundle
  participant SV as Services in cell IN
  RA->>CP: PUT /countries/IN/profile (draft v42: add pack-in 1.3)
  CP-->>RA: validation + diff vs v41
  RH->>CP: POST /countries/IN/profile/v42/approve (effective 2026-12-01)
  CP->>S3: publish signed bundle v42
  CP--)SV: control.profile.updated (IN, v42)
  SV->>S3: fetch, verify signature, hot-swap at effective time
```

## API (selected)
`GET /countries/{cc}/profile?version=`, `PUT /countries/{cc}/profile`, `POST …/approve`, `GET /shard-map?country=`, `GET /flags?cell=`, `GET /registry/countries`.

## Data
`country_profiles` (versioned JSON + schema), `country_registry`, `shard_map`, `pack_versions`, `workflow_definitions`, `feature_flags`, `approvals`.

## Events
- **Publishes:** `control.profile.updated`, `control.shardmap.updated`, `control.flags.updated`.

## Scaling profile
Tiny write volume. Reads are served from **local caches in every service** (bundle loaded at start), so the control plane is **not on any request path**.

## Limitations
- A cell must run on its last good bundle if the global plane is down. Bundles are signed and cached locally; cells never block on the control plane.
- A bad config can affect many countries. Mitigations: effective dates, canary country, instant rollback to the previous version.

## Trade-offs
| Choice | Alternative | Why |
|---|---|---|
| Own control plane | AWS AppConfig / LaunchDarkly | Domain-specific validation (workflows, shard maps) and approval rules. AppConfig could deliver bundles underneath. |
| Signed bundles in S3 per cell | Live API calls | Cell independence and zero hot-path dependency. Costs: propagation takes seconds, not milliseconds. |
