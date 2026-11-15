# Scaling Path — 10M → 200M → 1B users

**Principle: scale by adding cells and shards, not by making things bigger.** Every component has a known per-unit capacity; growth means adding units, and every hard limit is written down with its escape route.

## 1. Stages
| Dimension | Stage A — 10M (pilot, 1 country) | Stage B — 200M (20 countries, worst case today) | Stage C — 1B |
|---|---|---|---|
| Cells | 1 (APAC-IN) | ~6 (IN, BR, US, EU, AFRICA/ME, SEA) | ~15–20: large countries split by state/region groups (IN-north, IN-south, …) |
| Cases/day | 10M | 200M | ~1B |
| Comments/day | up to 100M | up to 2B | up to 10B |
| Physical DB clusters (case) per country | 4 (16 logical shards each) | 4–16 | 16–64 (+ extra country indices for the largest) |
| Kafka | 1 MSK cluster, 6 brokers | 1 per cell | 1–2 per cell (CDC and domain events split) |
| Redis | 1 cluster (cache) + 1 (rate limits) | Same per cell | Same per cell, more shards |
| EKS | 1 cluster, ~150 nodes at peak | 1 per cell | 1–2 per cell (blast radius) |
| Snowflake | 1 account (APAC) | 1 per region | Multi-cluster warehouses; RAW summarised after 1 year |
| Peak req/s | ~5–10K | ~100K | ~500K |

## 2. What scales how
| Component | Scaling mechanism | Trigger |
|---|---|---|
| Stateless services | HPA (CPU/RPS) + Karpenter nodes; KEDA on Kafka lag for consumers | Automatic |
| Case/grower data | Move logical shards to new physical clusters (logical replication of a schema → cutover → shard map update); IDs never change | Cluster > 60% CPU/IOPS/storage |
| Very large countries | Assign **additional country indices** (6-bit field has 64 values; 20 used) → another 64 logical shards; new growers go to the new index | Country > 64 physical clusters' worth of load |
| Cells | New cell = Terraform stack + Argo CD ApplicationSet entry; edge cell-router maps `country (+ state group) → cell` | Cell approaches limits or blast-radius policy |
| Kafka | Add brokers; partitions pre-provisioned for Stage C; split CDC into its own cluster at Stage C | Broker CPU/network > 60% |
| Redis | Add shards (cluster mode resharding online) | Memory > 70% |
| Snowflake | Multi-cluster warehouses; separate ingest/serve/analytics warehouses; clustering keys | Queue time, credit budget |
| Realtime connections | More realtime-gateway pods; connection count per pod | > 70% of tested connections per pod |
| Edge | CloudFront + WAF scale automatically; cache static assets and scan "UNKNOWN" responses | — |

## 3. Hard limits and escape routes
| Limit | Value | Hit at | Escape route |
|---|---|---|---|
| `case_id` shard field | 64 logical shards per country index | Single country > ~60K case writes/s | Extra country index (no format change) |
| `case_id` country field | 64 indices | > 64 countries + overflow indices | ID v2 (new epoch, re-split bits); public `case_no` v2 uses a reserved leading `0` |
| `case_no` daily counter | 1M per shard per day | > 64M cases per country index per day | Extra country index / CC slot |
| `case_id` time field | 41 bits from 2026 | ~2095 | — |
| Kafka partitions per topic | Fixed key → partition mapping | Consumer parallelism ceiling | Pre-provision; new topic version with more partitions + dual-write migration |
| Postgres per physical cluster | Write IOPS / WAL rate / replication slot lag | ~10–20K row writes/s per cluster | Move shards (more clusters) |
| Debezium per cluster | One connector task per slot | ~20–50K events/s per connector | More physical clusters = more connectors |
| Keycloak per cell | DB-backed user store | ~100M users per cell | Cell split; lighter OTP token service for growers |
| SMS/WhatsApp | Provider throughput & **cost** (linear with users) | Always | Batching, push-first, in-app inbox |
| Snowflake cost | Linear with data and queries | Always | Clustering, RAW summarisation, result caching, query guardrails |

## 4. Costs that grow linearly (watch list)
1. **Messaging** (SMS/WhatsApp), the largest variable cost at 1B users. Push and in-app first; SMS only for OTP and critical messages.
2. **Snowflake credits** (ingest + MERGE + serving). Separate warehouses and resource monitors per cell.
3. **Storage**: hot Postgres (bounded by the 90-day window), S3 media (lifecycle to Glacier), Snowflake RAW (summarise after 1 year).
4. **Cross-AZ data transfer** (Kafka replication, RDS Multi-AZ). Use rack-aware consumers (fetch from closest replica).

## 5. Load-test gates per stage
| Gate | Test |
|---|---|
| Stage A go-live | k6: 2× Stage A peak for 2 h on staging cell; chaos (kill a DB writer, an MSK broker, Redis primary) with SLOs held |
| Each new country | Replay its season curve at 2× in staging; shard-map and cell-router verified |
| Stage C readiness | Cell split rehearsal (move a state group to a new cell); 64-cluster shard map; ID v2 dry run |
