# ADR-006: Aurora PostgreSQL for shard clusters, RDS PostgreSQL for small service DBs
- **Status:** Proposed · 2026-11-15 (the requirement said "RDS"; this refines it)
- **Context:** Shard clusters are write-heavy, multi-TB and failover-sensitive (Debezium slots). Smaller services (claims, catalog, control plane) are modest.
- **Decision:**
  - **Aurora PostgreSQL** for case/grower shard clusters: faster failover, storage auto-growth, up to 15 low-lag replicas.
  - **RDS PostgreSQL** (Multi-AZ) elsewhere for cost.
  - Verify logical-replication slot behaviour on failover for the chosen engine version before go-live.
- **Consequences:** Aurora costs more per I/O (evaluate I/O-Optimized). Two engines to operate, but the same Postgres dialect.
- **Alternatives:** RDS everywhere (cheaper, slower failover, storage management), self-managed Postgres on EC2 (ops cost).
