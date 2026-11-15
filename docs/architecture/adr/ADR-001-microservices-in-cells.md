# ADR-001: Coarse-grained microservices deployed in regional cells
- **Status:** Accepted · 2026-11-15
- **Context:** Worst-case load is 200M cases + 2B comments per day across 20 countries, growing to 1B users. Workloads differ by orders of magnitude (scan reads, comment writes, Snowflake archive queries, bursty notifications). Data residency differs per country. Many teams, frequent country-specific releases.
- **Decision:** 14 services aligned to bounded contexts, each a hexagonal modular app with its own database, integrated asynchronously via outbox + Kafka. The full service set is deployed per **cell** (large country or region). A global control plane holds config only.
- **Consequences:**
  - Plus: independent scaling, deploys and failure isolation; residency by construction.
  - Minus: distributed-systems cost (tracing, sagas, contract versioning, more infrastructure per cell). Mitigated by shared libs, contract-first, max 2 synchronous hops.
- **Alternatives:**
  - Modular monolith: rejected for scaling-profile and blast-radius reasons. Still the internal structure of each service.
  - Fine-grained microservices (50+): rejected for operational cost.
