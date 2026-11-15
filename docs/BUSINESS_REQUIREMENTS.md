# ServiceHub — Consumer After-Sales Service Platform
### Business Requirements Document (BRD) · v0.1 (draft for review) · 2026-11-14

> Evolution of the "Work Orders Lite" take-home into a real-world **B2C after-sales service platform**.
> Work orders become **Service Requests (SRs)** raised by consumers against products they own, fulfilled by
> support agents and field technicians, billed and reconciled by finance, and governed by admins.

---

## 1. Business context

**Who runs it:** a consumer-durables company (home appliances / electronics: ACs, washing machines, TVs, water purifiers)
that sells through retail and needs to service what it sold — in warranty, under paid service plans, or as paid repairs.

**Problem today (typical):** service requests arrive by phone/email, get tracked in spreadsheets, technicians are
dispatched by phone, cash collected in the field is hard to reconcile, warranty fraud is common, and customers have no
visibility into "when is someone coming?".

**What ServiceHub delivers:**
1. Customers self-serve: register products, check warranty, raise and track service requests, book a visit slot, pay, rate.
2. Support teams triage, resolve remotely where possible, and escalate.
3. Dispatch assigns the right technician (skill, zone, availability); technicians work from a mobile app, offline-capable.
4. Spare parts are reserved, consumed and returned with stock accountability.
5. Finance gets quotes → invoices → payments → refunds → technician payouts on an auditable ledger, reconciled daily.
6. Admins control and **monitor** access: roles, scopes, approvals, access reviews, audit trail.

### Scale targets
| Metric | Target |
|---|---|
| Registered customers | 10M |
| Registered products | ~18M (≈1.8 per customer) |
| Service requests | ~6M / year (~16K/day avg, ~50K/day seasonal peak — summer AC season) |
| Workforce | ~3K support agents, ~15K technicians, ~200 finance/admin staff |
| Peak API load | **1,000 req/s** (status checks + notifications dominate; ~85% reads) |
| Activity/audit events | ~60M / year |
| Media (photos/videos of faults, proof of purchase) | ~1–2 TB / month |

---

## 2. Actors & roles

Two **separate identity populations** — they have different risk profiles and must not share login flows:

| Population | Role | Description | Scope |
|---|---|---|---|
| **Customers** | `CUSTOMER` | Consumer who owns products | Own data only |
| **Workforce** | `SUPPORT_AGENT` (L1) | Handles SR triage, remote resolution, customer comms | Assigned queue / region |
| | `SUPPORT_SUPERVISOR` (L2) | Escalations, reassignment, goodwill credits (≤ limit) | Region |
| | `DISPATCHER` | Schedules and assigns technicians | Service zone(s) |
| | `TECHNICIAN` | Performs visits via mobile app | Jobs assigned to them |
| | `SERVICE_CENTER_MANAGER` | Owns technicians, stock, SLAs of a service center | Service center |
| | `INVENTORY_MANAGER` | Spare-part stock, transfers, defective returns | Warehouse(s) |
| | `FINANCE_EXECUTIVE` | Invoices, refunds (maker), reconciliation | Region / company |
| | `FINANCE_MANAGER` | Approves refunds/write-offs (checker), payouts, period close | Company |
| | `SECURITY_ADMIN` | Users, roles, access reviews, audit log | Company |
| | `PLATFORM_ADMIN` | Catalog, SLA policies, pricing, zones, feature flags | Company |
| | `AUDITOR` | Read-only access to finance + audit trails | Company, time-boxed |

### Authorization model: RBAC + scopes (ABAC) + governance rules
- **Permissions, not roles, in code** — e.g. `sr:read`, `sr:assign`, `refund:create`, `refund:approve`, `user:role:grant`.
  Roles are bundles of permissions, configurable without deploys.
- **Scope attributes** restrict *which* records: region, service center, zone, "assigned to me", "own records".
- **Segregation of duties (SoD):** the same person cannot create *and* approve a refund; cannot grant a role to themselves;
  `FINANCE_*` and `SECURITY_ADMIN` cannot be combined.
- **Maker–checker:** refunds > ₹/$ threshold, write-offs, price overrides, role grants of privileged roles.
- **Just-in-time elevation / break-glass:** time-boxed elevated access with reason, auto-expiry, and alert.
- **Customer data minimization:** agents see masked phone/address (`98xxxxxx21`) until they open an SR they are working on;
  unmasking is logged.

### Permission matrix (abridged)
| Capability | CUST | AGENT | SUPV | DISP | TECH | SCM | INV | FIN-E | FIN-M | SEC | PLAT | AUD |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Register product / view own products | ✅ | view | view | – | view (job) | view | – | – | – | – | – | – |
| Raise SR | ✅ own | ✅ on behalf | ✅ | – | – | – | – | – | – | – | – | – |
| View SR | own | queue/region | region | zone | assigned | center | – | linked | ✅ | – | – | ✅ |
| Triage / resolve remotely | – | ✅ | ✅ | – | – | – | – | – | – | – | – | – |
| Book / reschedule slot | ✅ own | ✅ | ✅ | ✅ | – | ✅ | – | – | – | – | – | – |
| Assign technician | – | – | ✅ | ✅ | – | ✅ | – | – | – | – | – | – |
| Update job (on-site, parts, complete) | – | – | – | – | ✅ assigned | – | – | – | – | – | – | – |
| Approve quote | ✅ own | – | – | – | – | – | – | – | – | – | – | – |
| Create refund / credit note | – | – | goodwill ≤ limit | – | – | – | – | ✅ (maker) | ✅ | – | – | – |
| Approve refund | – | – | – | – | – | – | – | – | ✅ (checker) | – | – | – |
| Manage stock | – | – | – | – | van stock (consume) | center | ✅ | – | – | – | – | – |
| Manage staff users & roles | – | – | – | – | – | own techs (no role grant) | – | – | – | ✅ | – | – |
| Access reviews / audit log | – | – | – | – | – | – | – | – | – | ✅ | – | ✅ |
| Catalog, SLA, pricing config | – | – | – | – | – | – | – | – | pricing approve | – | ✅ | – |

---

## 3. Functional requirements by module

IDs: `<MODULE>-<n>`. Priority: **M** = MVP, **P2/P3/P4** = later phases (see §6).

### 3.1 Identity & Access (IAM)
| ID | Requirement | Pri |
|---|---|---|
| IAM-1 | Customer sign-up / login with mobile OTP **or** email+password; email verification | M |
| IAM-2 | Workforce login via company SSO (OIDC) with **mandatory MFA**; no local passwords for staff | M (OIDC via Keycloak), P3 (corp IdP federation) |
| IAM-3 | Short-lived access tokens (≤15 min) + rotating refresh tokens; logout revokes; "log out all devices" | M |
| IAM-4 | OTP abuse protection: per-number/IP/device rate limits, CAPTCHA after N attempts, SMS-pumping fraud detection | M |
| IAM-5 | Account lockout & anomaly alerts (new device, impossible travel) for staff | P2 |
| IAM-6 | Customer account deletion & data export (privacy rights) | P2 |
| IAM-7 | Social login (Google/Apple) for customers | P3 |

### 3.2 Customer & Product Registry
| ID | Requirement | Pri |
|---|---|---|
| PRD-1 | Product catalog: categories, models, skills required to service, standard warranty months, serviceable parts | M |
| PRD-2 | Customer registers a product: model, **serial number**, purchase date, retailer, invoice upload (image/PDF) | M |
| PRD-3 | Serial validation against manufacturing records; **one active owner per serial** (duplicate claim → flagged for review) | M |
| PRD-4 | Warranty entitlement auto-computed (standard + extended + service plan); shown to customer and agent | M |
| PRD-5 | Proof-of-purchase verification queue (agent approves/rejects; OCR pre-fill in P3) | P2 |
| PRD-6 | Ownership transfer (sell product) with consent of current owner | P3 |
| PRD-7 | Multiple service addresses per customer, geocoded | M |

### 3.3 Service Plans (Extended warranty / AMC)
| ID | Requirement | Pri |
|---|---|---|
| PLN-1 | Purchase a service plan for a registered product (1/2/3 years; includes N preventive visits) | P3 |
| PLN-2 | Entitlement engine: decides labor/parts chargeable vs covered per SR | P2 (warranty only), P3 (plans) |
| PLN-3 | Auto-schedule preventive-maintenance visits; renewal reminders | P3 |

### 3.4 Service Requests (core — evolution of "work orders")
| ID | Requirement | Pri |
|---|---|---|
| SR-1 | Customer raises SR against a registered product: issue category, symptoms, description, photos/video (≤5 files, ≤50MB) | M |
| SR-2 | Agent raises SR on behalf of a customer (phone channel); channel recorded | M |
| SR-3 | Human-readable ID (`SR-2026-00412345`); customer-visible status timeline | M |
| SR-4 | Lifecycle as an explicit **state machine** (see below); invalid transitions rejected | M |
| SR-5 | Priority auto-derived (product category, safety issue e.g. gas leak/sparking = P1, plan tier) and overridable by agent with reason | M |
| SR-6 | Duplicate detection: open SR exists for same product → link instead of creating new | M |
| SR-7 | Two comment streams: **customer-visible** conversation and **internal notes** | M |
| SR-8 | Activity/audit trail for every change (who/what/when/before→after) | M |
| SR-9 | Customer can cancel before technician is en route; reschedule up to 2 times | M |
| SR-10 | Reopen within 7 days of closure → linked "repeat repair" (counts against first-time-fix KPI) | P2 |
| SR-11 | Queue views for agents: filter by status/priority/region/SLA breach risk, search by SR id / phone / serial | M |

**SR state machine**
```
NEW ──► TRIAGED ──► RESOLVED_REMOTELY ─────────────────────────────► CLOSED
          │   └──► AWAITING_CUSTOMER ──(reply)──► TRIAGED
          ▼
      SCHEDULED ──► TECHNICIAN_ASSIGNED ──► EN_ROUTE ──► ON_SITE
                                                      │
              ┌───────────────────────────────────────┤
              ▼                                       ▼
      AWAITING_PARTS ──(parts ready)──► SCHEDULED   QUOTE_PENDING_APPROVAL ──(declined)──► CLOSED (visit fee)
                                                      │(approved / not chargeable)
                                                      ▼
                                              WORK_COMPLETED ──► PAYMENT_PENDING ──► CLOSED
Any non-terminal ──► CANCELLED        CLOSED ──(≤7 days)──► REOPENED ──► TRIAGED
Escalation is a flag (L1→L2→manager), not a state.
```

### 3.5 Support Desk
| ID | Requirement | Pri |
|---|---|---|
| SUP-1 | Skill/region-based queues; agent picks or is auto-assigned (round-robin with capacity cap) | M |
| SUP-2 | Remote-resolution playbooks (troubleshooting steps per model/symptom) | P2 |
| SUP-3 | Canned responses, templated customer messages | P2 |
| SUP-4 | Escalation rules: SLA risk, repeat SR, VIP/plan tier, negative sentiment | P2 |
| SUP-5 | Goodwill credit by supervisor within configurable limit (above → finance approval) | P3 |
| SUP-6 | In-app chat (customer ↔ agent), real-time | P4 |

### 3.6 Field Service & Dispatch
| ID | Requirement | Pri |
|---|---|---|
| FLD-1 | Technician profile: skills (product categories), certifications, home service center, service zones (polygons), shift calendar, leave | M |
| FLD-2 | Customer books a **visit slot** (e.g. 2-hour windows) from real capacity for their zone & product skill; slot held 10 min during booking | P2 (M: dispatcher books manually) |
| FLD-3 | Manual assignment by dispatcher with conflict warnings (overlap, skill mismatch, out-of-zone) | M |
| FLD-4 | Auto-assignment: score = skill match, distance/travel time, current load, rating, first-time-fix rate | P3 |
| FLD-5 | Technician mobile app (PWA): today's jobs, navigation link, check-in with **geo-fence**, job card, photos, parts used, customer signature | P2 |
| FLD-6 | **Offline-first**: job updates queued on device and synced when online, without duplicates or lost updates | P2 |
| FLD-7 | **Happy-code**: job completion confirmed by a 4-digit OTP the customer shares (prevents fake closures) | P2 |
| FLD-8 | Customer sees "technician en route" with ETA (live location while en route only — privacy) | P3 |
| FLD-9 | No-show handling: customer not home → reschedule flow + optional visit fee | P2 |

### 3.7 Parts & Inventory
| ID | Requirement | Pri |
|---|---|---|
| INV-1 | Parts catalog mapped to models (compatibility), price, warranty-coverable flag | P2 |
| INV-2 | Stock per location: central warehouse, service center, **technician van** | P2 |
| INV-3 | Reserve parts for an SR; consume on job completion; release on cancel | P2 |
| INV-4 | Defective-part return tracking (needed for manufacturer warranty claims) | P3 |
| INV-5 | Transfers between locations; reorder-point alerts; cycle counts with variance approval | P3 |

### 3.8 Billing, Payments & Finance
| ID | Requirement | Pri |
|---|---|---|
| FIN-1 | Rate card: visit fee, labor by job type, parts price; region-specific; versioned with effective dates | P2 |
| FIN-2 | **Quote** generated on site; customer approves in-app (or happy-code) before chargeable work | P2 |
| FIN-3 | Tax-compliant **invoice** (sequential invoice numbers with no gaps per legal entity, tax lines, PDF) | P2 |
| FIN-4 | Online payments via gateway (cards/UPI/wallets) using hosted checkout — **no card data touches our servers** | P2 |
| FIN-5 | Cash/field collection by technician → technician cash balance → deposit & reconciliation | P3 |
| FIN-6 | Refunds & credit notes with **maker–checker**; partial refunds; refund to original payment method | P3 |
| FIN-7 | **Double-entry ledger**: every money movement = balanced journal entries; balances are derived, never edited | P3 |
| FIN-8 | Daily **reconciliation** of gateway settlement files vs ledger; mismatches into an exceptions queue | P3 |
| FIN-9 | Technician payouts: per-job commission + incentives − cash held; monthly payout run with approval | P3 |
| FIN-10 | Warranty-cost reporting (parts + labor absorbed per model) for manufacturer/quality teams | P3 |
| FIN-11 | Period close: lock a month; corrections only via adjusting entries | P4 |

### 3.9 Notifications
| ID | Requirement | Pri |
|---|---|---|
| NOT-1 | Event-driven notifications on SR state changes: SMS, email, push (WhatsApp in P3) | M (email/SMS) |
| NOT-2 | Templates with variables, per-language; customer channel preferences & opt-out; quiet hours | P2 |
| NOT-3 | Delivery tracking, retries with backoff, provider failover, dead-letter queue | P2 |

### 3.10 Feedback & Quality
| ID | Requirement | Pri |
|---|---|---|
| FBK-1 | CSAT (1–5) + comment after closure; NPS survey periodically | P2 |
| FBK-2 | Low rating → auto-creates a follow-up task for supervisor | P3 |

### 3.11 Admin, Governance & Access Monitoring
| ID | Requirement | Pri |
|---|---|---|
| ADM-1 | Staff user lifecycle: invite, activate, change scope, deactivate (immediate token revocation) | M |
| ADM-2 | Role & permission management; custom roles from permission sets; SoD conflict check on grant | M (fixed roles), P3 (custom) |
| ADM-3 | **Access monitoring dashboard**: who has which privileged roles, recent grants, dormant accounts (>30 days inactive), break-glass usage | P2 |
| ADM-4 | **Quarterly access reviews**: managers attest/revoke their reports' access; un-reviewed access auto-revoked | P3 |
| ADM-5 | Security audit log: logins, failed logins, role changes, PII unmask, exports, config changes — append-only, tamper-evident, searchable | M (write), P2 (UI) |
| ADM-6 | Config management: SLA policies, zones, rate cards, catalog — versioned, with approval for financial config | P2 |
| ADM-7 | Feature flags & maintenance banners | P3 |

### 3.12 Reporting & Analytics
| ID | Requirement | Pri |
|---|---|---|
| RPT-1 | Operational dashboards: open SRs by state/region, SLA breach risk, technician utilization | P2 |
| RPT-2 | KPIs: first-time-fix rate, mean time to resolve, repeat-repair rate, CSAT, cost per SR, revenue | P3 |
| RPT-3 | Finance reports: AR aging, refunds, collections, payouts, warranty cost by model | P3 |
| RPT-4 | Scheduled exports (CSV) — permission-gated and audit-logged | P3 |

### 3.13 SLA Management
| ID | Requirement | Pri |
|---|---|---|
| SLA-1 | SLA policies by priority/plan/region: time-to-triage, time-to-visit, time-to-resolve; business-hours calendars & holidays | P2 |
| SLA-2 | SLA timers pause in customer-waiting states (AWAITING_CUSTOMER, customer-requested reschedule) | P2 |
| SLA-3 | Breach-risk alerts at 75% / breach escalation at 100% | P2 |

---

## 4. Non-functional requirements
| Category | Requirement |
|---|---|
| Availability | Customer API & app **99.9%**; payment callbacks **99.95%**; technician app usable offline |
| Latency (p95) | Reads < 200 ms, writes < 400 ms, search < 500 ms, slot availability < 300 ms |
| Throughput | 1,000 req/s sustained peak with 2× headroom (load-tested at 2,000 req/s) |
| Durability | RPO ≤ 5 min (PITR), RTO ≤ 1 h; multi-AZ; quarterly restore drill |
| Security | OWASP ASVS L2; OWASP API Top-10 (object-level authz on every endpoint); TLS 1.2+; encryption at rest; **field-level encryption** for phone/address/email; secrets in a vault |
| Privacy | Consent capture; data export/deletion within 30 days; PII masking in logs; retention: SR data 7 yrs (tax), media 2 yrs, logs 90 days |
| Payments | PCI-DSS scope minimized to SAQ-A (hosted checkout); webhook signature verification; idempotent money operations |
| Auditability | Every state change, money movement and access change recorded with actor, timestamp, before/after |
| Observability | Distributed tracing, RED metrics per endpoint, business metrics (SRs created/min, payment failures), structured logs with `trace_id`, alerting on SLOs |
| Accessibility / i18n | WCAG 2.1 AA; multi-language templates; timezone-aware scheduling; currency in minor units |
| Compatibility | Customer web (responsive), technician PWA on mid-range Android, last 2 versions of major browsers |

---

## 5. Real-world engineering problems this design forces (and the techniques)

This is the part that makes it a non-toy project.

| # | Problem | Why it is hard | Technique |
|---|---|---|---|
| 1 | **Customer sees only their own data** at 10M users | IDOR is the #1 API vulnerability; one missed check leaks data | Ownership checks in a central policy layer (not per-controller), query scoping by `customer_id`, integration tests that try every endpoint with another customer's IDs |
| 2 | **Slot booking without double-booking** | Many customers race for the last slot in a zone | Capacity rows per (zone, skill, slot) updated with `UPDATE ... WHERE remaining > 0` (atomic), short-lived holds with TTL, expiry sweeper |
| 3 | **SR state machine** across actors | Concurrent agent + technician + customer actions | Explicit transition table, optimistic locking (`@Version`), domain events per transition |
| 4 | **Payments correctness** | Duplicate clicks, retried requests, webhooks arriving late/out of order/twice | Idempotency keys, payment state machine, webhook signature verification, store-then-process webhooks, idempotent consumers |
| 5 | **Money accuracy** | Floats, rounding, edits to history | Minor units (`long`) or `BigDecimal` with fixed scale; immutable double-entry ledger; corrections via reversal entries |
| 6 | **Reconciliation** | Gateway says paid, our DB says pending (or vice versa) | Daily settlement-file import, matching job, exceptions queue, status polling for stuck payments |
| 7 | **Cross-module workflows** (job done → invoice → payment → payout → notification) | Partial failures leave inconsistent state | Transactional **outbox** + Kafka, sagas with compensations, dead-letter queues |
| 8 | **Offline technician app** | Updates made offline, synced later, possibly twice | Client-generated command IDs (idempotent), last-write-wins only for safe fields, server-side conflict rules for state transitions |
| 9 | **SLA timers** for millions of SRs | Cron scanning all rows doesn't scale; timers must survive restarts | Durable timer table with `next_fire_at` index polled with `FOR UPDATE SKIP LOCKED` (or Temporal), business-hours calendar math |
| 10 | **Auto-dispatch** | Geo + skills + capacity + fairness | PostGIS for zones & distance, scoring function, later batch optimization |
| 11 | **Search** for agents (phone, serial, SR id, free text) | 6M SRs/year, 10M customers | Exact-match B-tree indexes for IDs/phone hash; Postgres `pg_trgm` → OpenSearch via CDC when needed |
| 12 | **Media uploads** | Large files, malware, cost | Presigned S3 uploads direct from client, async virus scan + thumbnailing, lifecycle rules to cold storage |
| 13 | **Notifications at scale** | Provider rate limits, failures, user preferences | Queue-based dispatcher, per-provider rate limiting, retries + DLQ, provider failover |
| 14 | **OTP / SMS fraud** | Bots trigger thousands of SMS = real money lost | Rate limits per number/IP/device, CAPTCHA, country allow-list, anomaly alerts |
| 15 | **Access governance** | Privilege creep, insider risk | Permission-based RBAC + scopes, SoD rules, maker–checker, access reviews, tamper-evident (hash-chained) audit log |
| 16 | **PII protection** | Agents need some data, not all | Field-level encryption, masking by default, audited unmask, log redaction |
| 17 | **Reporting without hurting production** | Analytics queries kill OLTP | Read replica for dashboards; CDC (Debezium) → warehouse for heavy analytics |
| 18 | **Seasonal peaks** | Summer AC season = 3× SR volume | Horizontal autoscaling of stateless services, load tests at 2×, queue-based smoothing |

---

## 6. Phased delivery

| Phase | Theme | Scope (requirement IDs) | Exit criteria |
|---|---|---|---|
| **1 – MVP** | Raise & resolve | IAM-1..4, PRD-1..4,7, SR-1..9,11, SUP-1, FLD-1,3, NOT-1, ADM-1,2,5 | Customer raises SR on a registered product; agent triages; dispatcher assigns technician; SR closes; full audit trail; tests + CI green |
| **2 – Field & Money** | Visits and getting paid | FLD-2,5,6,7,9, INV-1..3, FIN-1..4, NOT-2,3, SLA-1..3, PRD-5, ADM-3,6, RPT-1, FBK-1, SUP-2,3,4, SR-10, IAM-5,6 | Customer books a slot, technician completes offline-capable job with happy-code, quote → invoice → online payment works end-to-end |
| **3 – Finance & Governance** | Trust and control | FIN-5..10, INV-4,5, PLN-1..3, ADM-2(custom),4,7, FLD-4,8, RPT-2..4, SUP-5, FBK-2, PRD-6, IAM-2(federation), IAM-7 | Ledger balances, daily recon has < 0.1% exceptions, access reviews run, auto-dispatch live |
| **4 – Scale & Experience** | Growth | SUP-6, FIN-11, OpenSearch, CDC warehouse, multi-region DR, WhatsApp, AI-assisted triage | 2× peak load test passed, DR failover drilled |

---

## 7. Target technology (extends the take-home stack)
| Concern | Choice | Notes |
|---|---|---|
| Backend | Java 21, Spring Boot 3, **modular monolith** (modules = §3 sections) | Split out only Notifications / Payments if scale or compliance demands |
| Frontends | React + TypeScript: customer web, staff console, technician PWA | Shared design system; OpenAPI-generated clients |
| Identity | Keycloak (two realms: customers, workforce) | OIDC; MFA for workforce |
| Database | PostgreSQL 16 + PostGIS; Flyway; read replica | Partition activity/audit by month |
| Cache / rate limit | Redis | Slot holds, OTP throttling, hot lookups |
| Messaging | Kafka (outbox → topics) | Notifications, payments, search indexing, analytics |
| Object storage | S3 (MinIO locally) | Presigned uploads |
| Search | Postgres trigram → OpenSearch (Phase 4) | |
| Payments | Gateway with hosted checkout (Stripe / Razorpay — sandbox) | |
| Observability | OpenTelemetry, Prometheus, Grafana, Loki | |
| Delivery | Docker, docker-compose (local), Kubernetes + Helm, GitHub Actions, Terraform | |

---

## 8. Open questions (need answers before Phase 1 design is frozen)
1. Geography & currency: single country (e.g., India with GST + UPI) or multi-country? Drives tax, payments, data residency.
2. Does the company use **third-party service partners** (franchise service centers) in addition to in-house technicians? (Adds partner org isolation — multi-tenancy comes back for partners.)
3. Is a **native mobile app** required, or is a PWA acceptable for technicians and customers?
4. Is the SR channel only app/web, or also call center (telephony integration) and WhatsApp?
5. Which payment gateway, and is cash-on-completion allowed?
6. Is this still the take-home submission? If yes: keep the take-home scope as-is and present this BRD as the roadmap in ARCHITECTURE/DECISIONS; if no: proceed with Phase 1 as defined above.
