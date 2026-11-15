# GrowerCare — Global Grower & Channel Service Platform
### Business Requirements Document · v0.2 · 2026-11-14
*Design exercise modelled on a global crop-science company (Syngenta-like). Supersedes `../BUSINESS_REQUIREMENTS.md` (appliance version). Company context: `01_COMPANY_ANALYSIS.md`.*

---

## 1. Vision & scope

One global platform where **growers**, **channel partners** (distributors, retailers, drone operators) and **company staff** raise, work and close **Field Cases** — advice requests, product complaints, seed claims, adverse incidents, counterfeit reports, spray-service bookings, demo plots, container returns, channel claims — with:
- **Region- and country-specific behaviour** (rules, workflows, languages, channels, dashboards) driven by configuration ("country profiles" and "compliance packs"), not code forks.
- **Business access control by geography and business unit**: who sees what is bounded by Region → Country → Territory and by BU (Crop Protection / Seeds / Biologicals / Professional Solutions).
- **Data residency by design**: personal data lives in the region where it is collected.

**Out of scope:** ERP/general ledger, manufacturing, R&D trials management, full farm-management (Cropwise-like) features. These integrate; they aren't rebuilt.

### Scale targets
| Metric | Target |
|---|---|
| Registered users | 10M (≈9.5M growers, ≈400K channel-partner users, ≈30K staff) |
| Countries at full rollout | 40+ (in 6 regional cells) |
| Cases | ~8M/year, strongly **seasonal per hemisphere** (India Kharif Jun–Jul, Brazil Safra Sep–Dec, US spring Apr–May) |
| Peak API load | ~~1,000 req/s global~~ → **superseded 2026-11-15:** worst case is 10M users per country × 20 countries × 1 case per user per day (+ 5–10 comments per case), i.e. ~20K writes/s and ~100K req/s globally. See `docs/architecture/05_SCALING_10M_TO_1B.md` |
| Product-authenticity scans | Up to 50% of traffic during peak season; must be cheap & cacheable |
| Media | Field photos/videos ~3 TB/month |

---

## 2. Organisation & geography model

```
GLOBAL (HQ)
 └─ REGION        NA · LATAM · EAME (Europe, Africa, Middle East) · APAC · CHINA*
     └─ COUNTRY   e.g. IN, BR, US, DE, KE
         └─ TERRITORY / SALES AREA   (state, district, município, county groups)
             └─ ZONE (polygon)       field agronomist coverage
BUSINESS UNIT dimension (orthogonal): CP · SEEDS · BIO · PROF
CHANNEL dimension: Distributor ─► Retailer/Dealer ─► Grower
```
*\*China operates as an isolated cell with its own deployment and admin; only anonymised aggregates leave it.*

Every record carries `region`, `country`, `territory_id`, `bu`, and where applicable `partner_org_id`. These are the attributes access control and data residency enforce.

---

## 3. Personas & roles

| Group | Role | Access scope (default) |
|---|---|---|
| **Growers** | `GROWER` | Own farms, own cases |
| **Channel partners** | `DISTRIBUTOR_ADMIN`, `DISTRIBUTOR_USER` | Own org + downstream retailers' channel data (no grower PII unless the case is shared) |
| | `RETAILER_ADMIN`, `RETAILER_USER` | Own shop; cases raised at/for their shop |
| | `DRONE_OPERATOR` | Assigned spray jobs only |
| **Field staff** | `FIELD_AGRONOMIST` | Assigned zones; assigned cases |
| | `TERRITORY_MANAGER` | Territory (all cases, retailers, demos) |
| **Central functions (country)** | `CARE_AGENT` (call center) | Country; masked PII by default |
| | `QUALITY_INVESTIGATOR` | Country + BU complaints & claims |
| | `STEWARDSHIP_OFFICER` | Country adverse incidents, container return |
| | `REGULATORY_AFFAIRS` | Country product registry, regulatory reports |
| | `PRODUCT_SECURITY` | Counterfeit cases (country or region) |
| | `FINANCE_ANALYST` (maker) / `FINANCE_APPROVER` (checker) | Country claims, compensations, rebates |
| **Management** | `COUNTRY_MANAGER` | Country dashboards (all BUs or assigned BU) |
| | `REGIONAL_HEAD` | Region dashboards; drill-down to country aggregates; case detail only with justification |
| | `GLOBAL_EXECUTIVE` | Global **aggregated, anonymised** dashboards |
| **Governance** | `COUNTRY_ADMIN` | Users/roles inside the country |
| | `REGIONAL_ADMIN` | Country admins & config inside the region |
| | `GLOBAL_PLATFORM_ADMIN` | Global config, compliance-pack versions; **no personal data access** |
| | `DPO` (per region) | Privacy requests, consent records, data-access logs |
| | `AUDITOR` | Read-only, time-boxed, scoped grant |

---

## 4. Business access control (region-aware)

### 4.1 Model
**Permission-based RBAC + attribute scopes (ABAC) + data-classification rules**, evaluated centrally (policy engine, e.g., OPA/Cedar-style policies), never ad-hoc in controllers.

```
ALLOW action ON resource IF
     role grants permission(action)
 AND resource.region   ∈ grant.regions
 AND resource.country  ∈ grant.countries        (or grant = ALL in region)
 AND resource.territory∈ grant.territories      (or ALL in country)
 AND resource.bu       ∈ grant.business_units
 AND classification(resource.field) ≤ grant.clearance     (PII, SENSITIVE_PII, COMMERCIAL, PUBLIC)
 AND residency_rule(resource.cell, user.cell) permits
 AND no SoD conflict
```

### 4.2 Requirements
| ID | Requirement | Pri |
|---|---|---|
| ACC-1 | A role assignment is a **grant** = role + geo scope + BU scope + optional validity period | M |
| ACC-2 | Admin delegation is bounded: country admins grant only within their country, and never roles above their own level | M |
| ACC-3 | PII masked by default for central/management roles; **unmask requires a reason**, is logged, and is rate-limited | M |
| ACC-4 | Cross-border data access (e.g., a regional head in Singapore viewing an Indian grower's PII) is allowed only where the residency rule allows it, and is logged as a **transfer event** | P2 |
| ACC-5 | Global roles see only aggregated, de-identified data (k-anonymity ≥ 10 on dashboard cells) | P2 |
| ACC-6 | Segregation of duties: claim maker ≠ approver; quality investigator cannot approve their own case's compensation; admin cannot grant themselves roles | M |
| ACC-7 | Approval limits per role per country currency (e.g., Territory Manager approves goodwill up to local-currency limit X) | P2 |
| ACC-8 | Just-in-time elevation (e.g., temporary access to another country during a crisis) with expiry, approver, and alert | P3 |
| ACC-9 | **Access monitoring dashboard** per scope: privileged grants, cross-border accesses, unmask events, dormant accounts, grants expiring | P2 |
| ACC-10 | **Quarterly access certification**: line managers re-certify grants; anything not re-certified is auto-revoked | P3 |
| ACC-11 | Partner-org isolation: a retailer can never see another retailer's data (classic multi-tenant isolation, row-level security) | M |
| ACC-12 | Grower consent controls what partners see (e.g., the grower allows their retailer to view a complaint) | P2 |

---

## 5. Country profiles & compliance packs (how "global but local" works)

A **Country Profile** is versioned configuration, approved by Regulatory Affairs + Regional Admin:

| Profile section | Examples |
|---|---|
| Locale | Languages (IN: en, hi, mr, te, ta, kn, gu, pa, bn…), date/number formats, units (ha/acre) |
| Channels enabled | App, web, WhatsApp, SMS, IVR, USSD (Africa), call center |
| Case types enabled | e.g., `SPRAY_SERVICE_BOOKING` disabled in EU countries without aerial derogation |
| Workflow variants | Extra steps/fields per case type (e.g., BR complaint requires the prescription number) |
| Regulatory clocks | Adverse-incident reporting deadlines per regulator |
| Identity rules | Login method (OTP vs password), KYC needs for partners (GSTIN, CNPJ, EIN, VAT ID) |
| Product registry source | Which registrations/labels apply |
| Tax & payments | GST / NF-e / VAT / sales tax; UPI / PIX / ACH / mobile money |
| Privacy | Consent texts, retention periods, DSAR deadline, residency cell |
| Seasons | Crop calendar per country (drives dashboards & capacity planning) |

**Compliance Packs** are code modules plugged in per jurisdiction, behind interfaces:

| Pack | Implements |
|---|---|
| `pack-in` | Retailer license validation, CIB&RC label rules, GST e-invoice (IRN) hooks, DPDP consent notices, drone pilot certificate check |
| `pack-br` | Receituário agronômico capture/validation, agronomist CREA check, inpEV container-return flow, NF-e hooks, LGPD |
| `pack-us` | Certified-applicator checks for RUP services, FIFRA 6(a)(2) adverse-effects report generation, state registration lookup, farm-exemption certificates |
| `pack-eu` | Member-state authorisation lookup, withdrawal sell-off/use-up deadlines, (EU) 2023/564 spray-record export, SUD training-certificate checks, GDPR |
| `pack-ke` / Africa | USSD flows, mobile-money payouts, agro-dealer registry |

| ID | Requirement | Pri |
|---|---|---|
| CFG-1 | Country profiles versioned with effective dates; changes need two approvals; full diff in the audit log | M |
| CFG-2 | Workflow definitions per (case type × country) are configurable (states, required fields, SLAs) within guardrails defined by HQ | P2 |
| CFG-3 | A new country can be launched by configuration + translation only, if an existing pack covers its jurisdiction | P3 |

---

## 6. Functional requirements

Priority: **M** = MVP · **P2/P3/P4** = later phases (§10).

### 6.1 Identity & onboarding
| ID | Requirement | Pri |
|---|---|---|
| IAM-1 | Grower sign-up by mobile OTP (low-literacy-friendly), optional email; assisted onboarding by retailer/agronomist with grower OTP consent | M |
| IAM-2 | Staff: corporate SSO (OIDC/SAML) + MFA; no local passwords | M |
| IAM-3 | Partner onboarding with KYC: tax ID (GSTIN/CNPJ/EIN/VAT), pesticide-sale license number + expiry, documents; approval by country team | M |
| IAM-4 | License expiry monitoring: partner sale-related features suspended automatically on expiry | P2 |
| IAM-5 | OTP fraud controls (per number/device/IP limits, SMS-pumping detection) | M |
| IAM-6 | Feature-phone access: IVR/USSD identity by phone number + PIN (Africa, parts of India) | P3 |

### 6.2 Grower, farm & product registry
| ID | Requirement | Pri |
|---|---|---|
| GRW-1 | Grower profile: farms/plots (geo-polygon or point), crops & season, area, preferred retailer | M |
| GRW-2 | **Product master** synced from regulatory/ERP: product, active ingredients, pack sizes, BU | M |
| GRW-3 | **Registration/label registry** per country: allowed crop × pest × dose × PHI (pre-harvest interval); registration status & validity; banned/withdrawn flags | M |
| GRW-4 | **Recommendations must be label-compliant** — the system blocks recommending a product for a crop/pest not on that country's label (advisory text can still mention non-chemical practices) | M |
| GRW-5 | Batch/lot master (mfg date, expiry, plant) synced from ERP; used by complaints, recalls, authenticity | M |
| GRW-6 | Withdrawal/recall management: product or batch flagged → affected cases, retailers and growers (where consented) notified; sell-off/use-up deadlines enforced in recommendations | P2 |

### 6.3 Field Case engine (evolution of "work orders")
| ID | Requirement | Pri |
|---|---|---|
| CASE-1 | Cases are typed (`AGRONOMY_ADVICE`, `PRODUCT_COMPLAINT`, `SEED_PERFORMANCE_CLAIM`, `ADVERSE_INCIDENT`, `COUNTERFEIT_REPORT`, `SPRAY_SERVICE_BOOKING`, `DEMO_PLOT`, `CONTAINER_RETURN`, `CHANNEL_CLAIM`); each type has its own state machine and SLA | M (first 4) |
| CASE-2 | Raised by grower (app/WhatsApp/IVR), retailer on behalf of grower, care agent, or field staff; channel recorded | M |
| CASE-3 | Rich evidence: geotagged photos/video, product pack photo, **batch number**, invoice/receipt, prescription (BR), spray date, dose, weather | M |
| CASE-4 | Routing: auto-assign by case type + country + territory/zone + BU + crop expertise + workload | M (rules), P3 (scoring) |
| CASE-5 | Field visit scheduling with agronomist calendar; check-in with geo-verification; offline visit forms | P2 |
| CASE-6 | Customer-visible conversation + internal notes; **multilingual** templates; optional machine translation for staff (flagged as such) | M / P3 |
| CASE-7 | Full activity/audit trail on every transition, assignment, field change and decision | M |
| CASE-8 | Optimistic locking; idempotent create (client-generated ID) for offline/WhatsApp retries | M |
| CASE-9 | Linking: same batch/territory complaints auto-clustered → "batch signal" alert to Quality | P2 |
| CASE-10 | Search & filters by case no., grower phone (exact), batch, product, status, territory, SLA risk | M |

**Example workflow — `PRODUCT_COMPLAINT`**
```
SUBMITTED ─► TRIAGED ─┬─► RESOLVED_BY_ADVICE (misuse/timing explained) ───────────────► CLOSED
                      ├─► NEED_INFO ─(grower replies)─► TRIAGED
                      └─► FIELD_INVESTIGATION ─► SAMPLE_COLLECTED ─► LAB_ANALYSIS
                                   │                                     │
                                   ▼                                     ▼
                           ROOT_CAUSE_DETERMINED  ◄──────────────────────┘
                     (product defect │ misuse │ weather │ counterfeit │ inconclusive)
                                   │
              ┌────────────────────┼──────────────────────────┐
              ▼                    ▼                          ▼
   COMPENSATION_PROPOSED     NO_COMPENSATION        ESCALATED_TO ADVERSE_INCIDENT / COUNTERFEIT
              │ (maker)            │                          (linked case)
              ▼                    ▼
   COMPENSATION_APPROVED (checker) ─► SETTLED_IN_ERP ─► CLOSED ─(≤30d)─► REOPENED
```
**Example workflow — `ADVERSE_INCIDENT`** (regulatory clock starts at *first awareness* by any employee or partner)
```
REPORTED ─► CLASSIFIED (human / animal / pollinator / environment / spill; severity)
         ─► INVESTIGATING ─► REGULATORY_ASSESSMENT ─► REPORT_FILED (regulator ref no.) ─► FOLLOW_UP ─► CLOSED
SLA = per-country regulator deadline from the compliance pack; alerts at 50/75/90%; breach → Regional Head + Global Stewardship.
```

### 6.4 Product authenticity & counterfeit
| ID | Requirement | Pri |
|---|---|---|
| AUTH-1 | Scan pack code (QR/serialised code) in app, WhatsApp or SMS → GENUINE / ALREADY_SCANNED_N_TIMES / UNKNOWN response in local language | P2 |
| AUTH-2 | Serialised code issuance from packaging lines (integration); codes unguessable (≥ 96-bit random), stored hashed | P2 |
| AUTH-3 | Anomaly detection: same code scanned in distant locations, scan spikes, unknown-code clusters → `COUNTERFEIT_REPORT` case + hotspot map | P3 |
| AUTH-4 | Reward/loyalty points for genuine scans (optional per country) with anti-gaming limits | P3 |

### 6.5 Stewardship & regulatory
| ID | Requirement | Pri |
|---|---|---|
| STW-1 | Adverse incident intake from any channel, including anonymous hotline | M |
| STW-2 | Regulator-specific report generation (fields & format per pack); submission reference stored | P2 |
| STW-3 | Safe-use training records for growers/operators (certificates, expiry) | P3 |
| STW-4 | Container return (BR mandatory, elsewhere optional): retailer receipt → collection center → proof; grower compliance status | P3 |
| STW-5 | Spray records export for EU professional users, per (EU) 2023/564 | P3 |

### 6.6 Spray-service marketplace (drones/custom application)
| ID | Requirement | Pri |
|---|---|---|
| SPR-1 | Grower books a spray job: plot polygon, crop, product (label-validated), date window | P3 |
| SPR-2 | Operator eligibility per country: pilot/operator certificates, aircraft registration, insurance, product approved for aerial use; **booking blocked where aerial application isn't permitted** | P3 |
| SPR-3 | Capacity & dispatch: operator calendars, travel distance, weather window (wind speed limits), no double booking | P3 |
| SPR-4 | Job completion with flight log/area sprayed; generates spray record for the grower | P3 |
| SPR-5 | Payment collection & operator payout via local rails (UPI/PIX/mobile money) through PSP | P4 |

### 6.7 Field force & demos
| ID | Requirement | Pri |
|---|---|---|
| FLD-1 | Agronomist/TM day plan: cases, demos, retailer visits; route list; offline mode | P2 |
| FLD-2 | Demo plots: plan → sow → observations (photos, yield) → farmer field day; lead capture | P3 |
| FLD-3 | Retailer visit notes, stock observations, scheme communication | P3 |

### 6.8 Claims, compensation & channel finance (system of engagement; ERP = system of record)
| ID | Requirement | Pri |
|---|---|---|
| FIN-1 | Compensation types: product replacement, replant seed, credit note to retailer, direct payout (where policy allows) | P2 |
| FIN-2 | Maker–checker approvals with role/country **approval limits in local currency**; multi-level above thresholds | P2 |
| FIN-3 | ERP integration: create credit note / payment request / replacement order; receive settlement status; **idempotent**, retry-safe, reconciled daily | P2 |
| FIN-4 | Channel scheme/rebate claims from retailers & distributors, with validation against sales data, and dispute workflow | P3 |
| FIN-5 | Country tax handling delegated to ERP / e-invoicing provider (GST IRN, NF-e, VAT); platform stores references | P3 |
| FIN-6 | Money in minor units + ISO-4217 currency; FX only for reporting (rates from treasury feed) | M |
| FIN-7 | Finance dashboards: claims cost by product/batch/territory, approval cycle time, leakage/fraud flags | P3 |

### 6.9 Notifications & engagement
| ID | Requirement | Pri |
|---|---|---|
| NOT-1 | Multichannel: push, SMS, WhatsApp (approved templates), email, IVR voice for low literacy | M (SMS/WhatsApp/email) |
| NOT-2 | Per-country templates & languages; consent & opt-out; quiet hours; regulatory messages can't be opted out of | P2 |
| NOT-3 | Seasonal advisories (pest-outbreak alerts by district/crop) — label-compliant content, approved by agronomy lead | P3 |

### 6.10 Region-specific dashboards
Dashboards are composed from **widgets enabled per country profile and filtered by the viewer's grant scope**. The same widget shows a district view to a TM, a country view to a Country Manager, and anonymised aggregates to Global.

| Audience | Core widgets (all regions) | Region-specific widgets |
|---|---|---|
| Territory Manager | Open cases by type/SLA, agronomist workload, demos, retailer activity | IN: Kharif/Rabi crop-stage pest alerts by district · BR: prescriptions pending, container-return compliance by retailer |
| Country Manager | Case volume & trends, SLA attainment, complaint rate per 1,000 units sold, CSAT, top products by complaints | US: state registration coverage, 6(a)(2) reporting queue · EU: products under withdrawal (sell-off/use-up countdown), spray-record export status · IN: retailer license expiries, counterfeit hotspots map |
| Quality | Complaints by batch/plant, signal clusters, lab turnaround | Batch recall impact by country |
| Stewardship / Regulatory | Adverse incidents by severity, **regulatory clock** status, overdue reports | Per-regulator submission log |
| Finance | Claims cost, approval aging, ERP sync failures | BR: barter-linked claims · IN: GST credit-note status |
| Product Security | Scan volumes, unknown-code hotspots, investigations | Africa/SE Asia/IN heat maps |
| Regional Head / Global | Cross-country comparison (normalised), seasonality, top risks | Anonymised only at Global |
| Admin / DPO | Access monitoring, cross-border access log, DSAR queue & deadlines, consent rates | Per-jurisdiction DSAR SLA (GDPR 1 month, others per law) |

| ID | Requirement | Pri |
|---|---|---|
| DSH-1 | Widget catalog with per-country enablement; layout per role | P2 |
| DSH-2 | All dashboard queries enforce the same ABAC scope as the APIs (no "dashboard backdoor") | M |
| DSH-3 | Near-real-time (≤ 5 min) operational widgets; daily refresh for analytical widgets | P2 |
| DSH-4 | Export gated by permission, watermarked, and audit-logged | P3 |

### 6.11 Privacy & data governance
| ID | Requirement | Pri |
|---|---|---|
| PRV-1 | Consent records per purpose (service, marketing, data sharing with retailer, research) with version of notice and language | M |
| PRV-2 | DSAR (access/correct/delete/port) workflow with jurisdiction-specific deadlines | P2 |
| PRV-3 | Retention schedules per country & record type (e.g., incident & regulatory records retained per legal requirement even after a deletion request — legal-hold override) | P2 |
| PRV-4 | Farm data ownership: grower can export their data and revoke sharing | P3 |
| PRV-5 | PII never in logs, analytics events, or URLs; field-level encryption for phone, national IDs, precise farm geolocation | M |

---

## 7. Non-functional requirements
| Category | Requirement |
|---|---|
| Availability | 99.9% per regional cell; authenticity-scan endpoint 99.95% (cached, read-only) |
| Latency p95 | Reads < 250 ms, writes < 500 ms in-region; scan verify < 150 ms |
| Peak | **Updated 2026-11-15 (confirmed worst case):** ~20K writes/s and ~100K req/s globally at Day 1 (200M cases + up to 2B comments per day); growth path to 1B users in `docs/architecture/05_SCALING_10M_TO_1B.md`. Each cell is load-tested at 2× its seasonal peak |
| Offline | Field apps fully usable offline for 72 h; sync conflicts resolved deterministically |
| Low bandwidth | Grower app works on 2G/3G: payload budgets, image compression on device, resumable uploads |
| DR | RPO ≤ 5 min, RTO ≤ 2 h per cell; backups stay in-region |
| Security | OWASP ASVS L2, OWASP API Top-10 (object-level authz tests on every endpoint), SSO+MFA for staff, secrets in vault, encryption at rest/in transit, annual pen test |
| Audit | Tamper-evident (hash-chained) audit log, retained per regulatory schedule |
| i18n / a11y | 25+ languages including RTL (Arabic); WCAG 2.1 AA for web; voice/IVR for low literacy |
| Observability | OpenTelemetry tracing, per-cell SLO dashboards, business KPIs, alerting on regulatory-clock risk |

---

## 8. Architecture direction (summary — full architecture in `docs/architecture/00_OVERVIEW.md`)

> **Update 2026-11-15:** the "modular monolith" below is superseded by **ADR-001: 14 coarse-grained microservices deployed per regional cell**. The cell model, data platform and identifier design below still apply.

**Regional cells + thin global control plane.**

```
                    GLOBAL CONTROL PLANE (no personal data)
   country profiles · compliance-pack versions · product master · policy bundles
   identity federation for staff · anonymised aggregate analytics
          │ config push                         ▲ de-identified aggregates
          ▼                                     │
 ┌──────────────┬──────────────┬──────────────┬──────────────┬──────────────┐
 │  CELL: NA    │ CELL: LATAM  │ CELL: EU     │ CELL: APAC   │ CELL: CHINA  │ (+ Africa/ME served from EU or own cell)
 │ (US)         │ (BR)         │ (EU)         │ (IN)         │ (isolated)   │
 │ API (modular │ same code,   │ same code    │ same code    │ separate ops │
 │ monolith) ×N │ pack-br      │ pack-eu      │ pack-in      │ + local IdP  │
 │ Postgres+RLS │ ...          │ ...          │ ...          │              │
 │ Redis, Kafka │              │              │              │              │
 │ S3 in-region │              │              │              │              │
 └──────────────┴──────────────┴──────────────┴──────────────┴──────────────┘
 Integrations per cell: ERP (credit notes, orders), e-invoicing, SMS/WhatsApp/IVR providers, PSPs, regulator portals
```
- Same codebase everywhere; behaviour differs through **country profile + compliance pack + feature flags**.
- Inside a cell: Spring Boot modular monolith (modules = §6), Postgres with row-level security on `partner_org_id`/`country`, Redis, Kafka via transactional outbox, S3 for media, OpenSearch later.
- Authenticity scans served from a read-optimised path (Redis/edge cache) because they are the highest-volume, lowest-value-per-request traffic.

### 8.1 Data platform: hot/cold split (detailed in `03_DATA_PLATFORM_DESIGN.md`)
| Layer | Technology | Role |
|---|---|---|
| OLTP (hot) | **Amazon RDS for PostgreSQL** (Multi-AZ) | Latest data: open cases + anything closed < 3 months; all master data |
| CDC | **Debezium** (PostgreSQL connector, pgoutput) on Kafka Connect | Streams every committed change; a second connector runs the Outbox Event Router |
| Streaming | **Apache Kafka** (Amazon MSK) + Schema Registry (Avro) | Durable, ordered per primary key; feeds Snowflake and domain consumers |
| Warehouse (cold + analytics) | **Snowflake** (one account per regional cell) | RAW change log → CURATED current state (idempotent `MERGE`) → SERVING Dynamic Tables / materialized views for archive queries & dashboards |
| Lifecycle | Archiver service | Verify (row-by-row hash) → purge-marker → hard delete from RDS; append-only tables dropped by monthly partition |
| Routing | API query router | Ranges within 3 months → RDS; anything older → Snowflake |

Requirements: **DATA-1 … DATA-14** (defined in `03_DATA_PLATFORM_DESIGN.md` §11).

### 8.2 Identifiers (detailed in `04_ID_GENERATION_DESIGN.md`)
- **Instagram-style, database-generated IDs** (decision 2026-11-15, replaces the dedicated ID-service design).
- Each country has 64 logical shards (Postgres schemas) on a few physical RDS clusters; `shard = hash(grower_id) % 64`.
- Internal `case_id`: 64-bit, generated by a per-shard PL/pgSQL function: 41-bit ms time, 6-bit country, 6-bit shard, 10-bit sequence.
- Public `case_no`: 15 digits `CC-DDDD-HH-SSSSSS-K` (country slot → region, day, shard, counter, Damm check digit), also generated in the DB.
- The app only routes by country and grower; reads route by decoding the ID.
- Requirements: **ID-1 … ID-12** (v0.2).

---

## 9. Hard real-world engineering problems (what makes this non-trivial)
| # | Problem | Technique |
|---|---|---|
| 1 | Region- and BU-scoped authorization everywhere, including dashboards & exports | Central policy engine (ABAC), query-level scoping, Postgres RLS as safety net, authorization test matrix in CI |
| 2 | Data residency with global management needs | Regional cells; global plane only receives de-identified aggregates; cross-border access logged as transfers |
| 3 | Label-compliant recommendations | Registry of crop × pest × product × country with effective dates; validation in every recommendation path |
| 4 | Regulatory clocks that must not be missed | Durable timers (`next_fire_at` + `SKIP LOCKED`, or Temporal), escalation chains, clock-start = first awareness |
| 5 | Configurable workflows without chaos | Versioned state-machine definitions per case type × country, guardrails, migration of in-flight cases on version change |
| 6 | Offline field apps + WhatsApp retries | Client-generated IDs, idempotent commands, deterministic conflict rules |
| 7 | Counterfeit detection at scale | Hashed serial codes, cached verification, geo/time anomaly detection via stream processing |
| 8 | ERP integration for money | Outbox → integration service, idempotency keys, daily reconciliation, exceptions queue |
| 9 | Seasonal "follow-the-sun" peaks | Per-cell autoscaling, pre-season load tests, queue-based smoothing |
| 10 | 25+ languages & low literacy | ICU message format, translation workflow, voice/IVR, image-first flows |
| 11 | Batch-level quality signals | Complaint clustering by batch/territory/time window, alert to Quality |
| 12 | Privacy-rights requests across cells | DSAR orchestrator fans out per cell; legal-hold overrides; proof of completion |

---

## 10. Phased delivery
| Phase | Scope | Pilot | Exit criteria |
|---|---|---|---|
| **1 – Core platform (MVP)** | IAM-1,2,3,5 · GRW-1..5 · CASE-1..4,6..8,10 (types: advice, complaint, seed claim, adverse incident) · ACC-1,2,3,6,11 · CFG-1 · NOT-1 · PRV-1,5 · DSH-2 · audit · DATA-1,2,3,12,13 (CDC to Snowflake live, no purge yet) · ID-1..7,9,12 | **1 country (India)**, 1 cell | Grower raises a complaint via app/WhatsApp, care triages, agronomist investigates, decision recorded; region-scoped access proven by tests; DPDP consent captured |
| **2 – Field, money & second region** | CASE-5,9 · GRW-6 · FIN-1..3 · AUTH-1,2 · STW-2 · ACC-4,5,7,9 · CFG-2 · DSH-1,3 · NOT-2 · PRV-2,3 · IAM-4 · DATA-4..8,10,11,14 (archive + purge after ≥ 30 days of clean reconciliation) · ID-8,10,11 | + **Brazil** (LATAM cell, `pack-br`) | Compensation approved and settled via ERP; prescription capture live in BR; authenticity scan live |
| **3 – Governance, stewardship & services** | DATA-9 · ACC-8,10 · AUTH-3,4 · STW-3..5 · SPR-1..4 · FLD-1..3 · FIN-4,5,7 · NOT-3 · PRV-4 · DSH-4 · CFG-3 | + **EU & US** cells | Access certification run; EU spray-record export; US 6(a)(2) queue; drone booking where permitted |
| **4 – Scale & intelligence** | SPR-5 · IAM-6 · AI pest/disease photo triage (label-constrained) · OpenSearch · CDC to warehouse · Africa USSD · China cell | Global | 2× seasonal peak per cell; DR drill per cell |

---

## 11. Open questions
1. **Pilot country for Phase 1.** India is proposed (large grower base, counterfeit problem, DPDP, many languages). Alternative: Brazil.
2. Should channel partners (distributors/retailers) **transact** on the platform (orders, stock) or only handle service and claims? This proposal: service/claims only, with orders in ERP and partner portals.
3. Should Cropwise-type farm data (fields, crop plans) be **integrated** (via API) or **duplicated** in a minimal form? This proposal: integrate, keep a minimal plot model.
4. ~~Does China need to be in scope?~~ **Decided 2026-11-15: China is out of scope for now.**
5. Approval-limit matrix and compensation policy per country — need input from Finance & Legal.
6. For the take-home submission: keep it to the original spec and attach this BRD as the roadmap, or build Phase 1 of this instead?
