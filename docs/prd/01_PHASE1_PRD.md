# GrowerCare — Phase 1 Product Requirements (India pilot)
**Version 0.1 · Draft · 2026-11-17** · Owner: product + architecture · Source of truth for *what* Phase 1 delivers; the BRD (`docs/syngenta/02_BUSINESS_REQUIREMENTS.md`) remains the source of *why*, and each service's specs define *how*.

---

## 1. Purpose
Phase 1 must make GrowerCare **functionally working end to end for one country (India)**, on the local stack first:
a grower or a retailer brings a crop problem in, it is triaged, routed, investigated and closed by the right staff, everyone sees only what they are allowed to see, and the grower is kept informed — all through a real web UI.

"Functionally working" means, for every journey in §4:
1. the services involved implement it, with specs and parameterized tests (convention 11/12);
2. it runs on the local stack (`growercare-local-platform`) with the released images;
3. it is driven through the **web UI** by a Playwright test, per persona;
4. the smoke test (`smoke.sh`) covers its API path.

**Exit criteria (BRD §10, Phase 1):** a grower raises a complaint via the app; care triages; an agronomist investigates; a decision is recorded; region/role-scoped access is proven by tests; DPDP consent is captured.

## 2. Personas in Phase 1
| Persona | Role(s) | Dev user | What they do in Phase 1 |
|---|---|---|---|
| Grower | `GROWER` | grower.in1, grower.in2 (IN), grower.br1 (BR, isolation only) | Register, confirm consent, manage farms, raise and follow their cases, comment, upload evidence, share a case with their retailer |
| Retailer | `RETAILER_USER` | retailer.in (501), retailer2.in (502) | Onboard growers (assisted), raise cases on a grower's behalf, see only own/shared cases |
| Care agent | `CARE_AGENT` | care.in, care.br | Find growers by phone, raise and triage cases, comment (public/internal), assign |
| Field agronomist | `FIELD_AGRONOMIST` | agro.in | Work assigned cases in Pune: investigate, transition, comment, attach |
| Territory manager | `TERRITORY_MANAGER` | tm.in | Oversee Maharashtra: queues, assignment, SLA risk |
| Country admin | `COUNTRY_ADMIN` | admin.in | Grant and revoke roles within India |

Out of Phase 1 (personas): quality investigator, stewardship, finance, product security, distributor, drone operator, regional/global management, DPO, auditor (their roles exist in IAM; their journeys come in Phase 2+).

## 3. Where we are (2026-11-17)
| Service | State | Specs |
|---|---|---|
| identity-access-service | **Working**: principals for every service, grant administration with delegation and SoD rules, audit, grant events | SPEC-IAM-01 |
| case-service | **Working**: case lifecycle and workflows (4 types), comments and sharing, SLA timers and routing, attachments with scan, grower replica from grower-service | SPEC-CASE-01…06 |
| grower-service | **Working**: register (self/assisted), read, update, find by phone, internal replica view | SPEC-GRW-01 |
| local platform | **Working**: Postgres, Redis, Keycloak, Kafka + Debezium, SeaweedFS; smoke 35/35 | SPEC-PLAT-01…06 |
| catalog-registry, notification, edge-gateway | **Not built** | — |
| Web UI | **Not built** | — |

Gaps that block the exit criteria: assisted growers can never reach `ACTIVE` (no consent/OTP); products/batches/farms are seeded copies; nobody is notified; there is no UI; case search by grower phone (CASE-10) is missing.

## 4. End-to-end journeys (acceptance criteria)
Each journey is one Playwright spec in the UI repo and one block in `smoke.sh`. IDs are stable.

### J1 — Grower signs up and confirms consent
1. A new grower opens the grower app, signs in (see decision D1), and registers: name, phone, district, language, preferred retailer.
2. The platform sends a one-time code to the phone (SMS, stubbed locally — D2); the grower enters it.
3. Consent is recorded per purpose (service — required; data sharing with retailer, marketing — optional) with the notice version and language.

**Accepted when:** the grower is `ACTIVE`; the consent record shows purpose, version, language, time; the phone is stored only as HMAC + ciphertext + last 4; a wrong code 5 times locks the attempt for 15 minutes.

### J2 — Retailer onboards a grower (assisted)
1. retailer.in registers a grower at the shop (name, phone, district).
2. The grower receives the code and confirms on their own phone (or reads it to the retailer, who enters it — D3).
3. Until confirmation the grower is `PENDING_CONSENT`; no case can be raised for them (SPEC-CASE-06 D4).

**Accepted when:** after confirmation the grower is `ACTIVE`; retailer.in sees the grower, retailer2.in does not; a case for the grower is accepted only after confirmation.

### J3 — Grower manages farms
The grower adds a farm (name, area, crop, location as a point; polygon later), edits it, and picks it when raising a case.

**Accepted when:** farms belong to exactly one grower; case-service accepts a farm only of the case's grower (replicated from grower-service, no seed); precise location is never shown to roles without FULL clearance (PRV-5).

### J4 — Grower raises a product complaint with evidence
1. The grower picks the case type (complaint), the product (searchable list) and the batch (validated against the product), the farm, and describes the problem.
2. Uploads photos (compressed on the device), a pack photo and the invoice.
3. Gets a case number (`71-…`) immediately.

**Accepted when:** only products registered in India are offered; an unknown batch is rejected with a clear message; uploads are scanned before anyone can download them; the case appears in the grower's list; the retailer sees it only if raised via them or shared.

### J5 — Care triages, routes and assigns
1. care.in sees the new case in the India queue, ordered by SLA risk.
2. Finds the grower by phone when the grower calls (CASE-10).
3. Triages (`SUBMITTED → TRIAGED`), adds an internal note and a public reply, assigns agro.in.

**Accepted when:** the queue shows only cases in scope; the grower name is masked for care (`R***** P****`); the assignee must have the case in scope; every step appears in the activity trail; the grower is notified of the public reply (J7).

### J6 — Agronomist investigates and closes
1. agro.in sees cases assigned to them, opens one, adds field evidence, moves it through the workflow (e.g. `FIELD_INVESTIGATION → ROOT_CAUSE_DETERMINED → NO_COMPENSATION → CLOSED`).
2. Records the decision in a public comment.

**Accepted when:** only allowed transitions are offered (workflow as data); stale updates are rejected (optimistic locking, the UI reloads); SLA timers stop at closure; the grower sees the decision and the closed status.

### J7 — Everyone is kept informed
- The grower gets an SMS/WhatsApp message (stubbed locally, visible in a dev mailbox UI) when: the case is created, a public comment is posted, the status changes to a customer-visible state, the case closes.
- Staff get an email on assignment.
- At most one message per case per 5 minutes (batching); no messages between 21:00 and 07:00 local time except OTPs.

**Accepted when:** each event produces exactly one message per recipient after batching; messages are in the grower's language (en, hi, mr for the pilot); internal notes never produce a grower message.

### J8 — Isolation and administration
1. admin.in grants agro.in a temporary role in another territory; revokes it.
2. Each persona tries to open a case outside their scope by URL.

**Accepted when:** out-of-scope cases and growers answer "not found" in the UI (404), never "forbidden"; Brazil users see nothing from India and vice versa; a revoked grant takes effect within seconds (event-driven cache eviction).

### J9 — Territory overview
tm.in opens an overview: open cases by status and type, SLA at risk / breached, agronomist workload, all within Maharashtra.

**Accepted when:** every number is computed with the same scope rules as the APIs (DSH-2, no dashboard backdoor).

## 5. Services and what each must deliver
| Service | Phase 1 scope | New specs |
|---|---|---|
| identity-access-service | As built; plus a `/me` profile for the UI (roles, permissions, scopes for `<Can>` rendering) — already exists; user listing for the admin UI | IAM-02 (user directory for admins) |
| grower-service | **Consent + OTP** (J1, J2), **farms** (J3), phone change with OTP; events `grower.consent.*`, `grower.farm.*` | GRW-02 farms, GRW-03 consent & OTP |
| case-service | As built; plus **search by grower phone** (CASE-10, via the replica's HMAC), farms and products/batches from the owning services instead of seeds, customer-visible status mapping for notifications | CASE-07 search, CASE-08 catalog & farm replicas |
| **catalog-registry-service** (new) | Products, batches, registrations per country (GRW-2/3/5); read APIs for the UI (search) and an internal view for case-service; events `catalog.*`. Label-compliance check (GRW-4) is **not** in Phase 1 (no recommendation feature yet) | CAT-01 |
| **notification-service** (new) | Consumes `case.*`, `comment.*`, `grower.*` (OTP); renders templates per language; batching window; quiet hours; dispatch through provider **ports** with local stubs (SMS/WhatsApp/email) and a dev mailbox to see messages | NOT-01 |
| **edge-gateway** (new) | Single entry for the UI: routes `/api/**` to services, validates JWTs, strips client routing headers, rate limits (stricter on OTP), CORS for the web apps | EDGE-01 |
| **gc-frontend** (new) | `grower-web` (responsive, installable) and `staff-console` (care, agronomist, TM, admin; partner route group for retailers) | UI specs per journey |

## 6. Functional requirements (traceability to the BRD)
| BRD ID | Requirement (short) | Phase 1 | Owner | Status |
|---|---|---|---|---|
| IAM-1 | Grower sign-up by mobile OTP; assisted onboarding with grower OTP consent | M | grower, IAM, notification | Partial (registration done; OTP to do) |
| IAM-2 | Staff SSO + MFA | M (local: Keycloak password) | IAM/Keycloak | Local only |
| IAM-3 | Partner onboarding with KYC | M → **Phase 2** (D6) | IAM | Deferred |
| IAM-5 | OTP fraud controls (limits per number/IP) | M | grower, edge | To do |
| ACC-1/2/6/11 | Grants, bounded delegation, SoD, partner isolation | M | IAM, all | Done (APIs); UI to do |
| ACC-3 | PII masked by default; unmask with reason | M (masking) / unmask → GRW-04 | all | Masking done |
| GRW-1 | Grower profile, farms, crops | M | grower | Profile done; farms to do |
| GRW-2/3/5 | Product master, registrations, batches | M | catalog | To do |
| CASE-1/2/3/4/6/7/8 | Typed cases, channels, evidence, routing, comments, audit trail, locking + idempotency | M | case | Done (APIs); UI to do |
| CASE-10 | Search by case no., grower phone, batch, product, status, territory, SLA risk | M | case | Partial (no phone search) |
| NOT-1 | SMS/WhatsApp/email | M | notification | To do |
| PRV-1 | Consent per purpose with notice version and language | M | grower | To do |
| PRV-5 | No PII in logs/events/URLs; field encryption for phone | M | all | Done |
| CFG-1 | Versioned country profiles | M (seeded profile for IN, read-only) | control-plane → **reference data** in Phase 1 (D5) | Partial |
| DSH-2 | Dashboard queries enforce ABAC | M | case (overview endpoint) | To do |
| ID-1…12 | Sharded DB-generated ids, case numbers | M | case, grower | Done |
| DATA-1/2/3 | CDC to the warehouse | M → **local: Kafka only** (D7) | data platform | Outbox → Kafka done |

## 7. Non-functional requirements for Phase 1 (local)
| Area | Requirement |
|---|---|
| Testability | Every journey: Playwright per persona against the local stack; API smoke; services keep ≥ 80 % line coverage and parameterized spec rows |
| Security | OWASP API Top-10 object-level checks per endpoint (isolation matrix); tokens only in memory in the UI (Authorization Code + PKCE); no PII in URLs or logs (also in the UI's analytics: none in Phase 1) |
| Performance (local smoke level) | Grower app first load ≤ 170 KB JS gzip; lists paginated; no endpoint slower than 1 s on the local stack |
| Accessibility | WCAG 2.1 AA basics checked with axe in Playwright |
| i18n | UI in en, hi, mr; messages in the same three |
| Resilience | A service being down shows a friendly error and retry in the UI; no blank screens; 503 from dependencies, never 500 |
| Local resources | The full stack + all services + UI must run on a 16 GB laptop (Docker VM ≤ 10 GB): JVM heaps capped per service, test runs separate from the running stack |

## 8. UI scope
**grower-web** (mobile-first): sign-in · registration + OTP + consent · my farms · raise a case (type, product/batch search, farm, description, photos) · my cases (status, timeline, conversation, attachments, share with retailer) · profile (language).

**staff-console**: sign-in · queue (scope-filtered, SLA risk, filters) · case detail (summary with masked PII, timeline, public/internal comments, attachments, transitions offered by the workflow, assign) · grower lookup by phone · territory overview (J9) · admin: users and grants (J8) · partner route group for retailers: onboard grower, my cases.

Not in Phase 1: field-pwa (offline), maps, realtime push (polling every 30 s instead), dashboards beyond J9, dark mode.

## 9. Decisions to agree with this PRD
| # | Decision (recommendation) | Why |
|---|---|---|
| D1 | **Grower sign-in:** Keycloak login with phone-as-username and password for the local Phase 1; OTP is used for **phone verification and consent** (J1/J2). Password-less OTP login (a custom Keycloak authenticator) is Phase 2 | A Keycloak SMS authenticator is a sizeable piece of its own; consent and phone proof are what the exit criteria need |
| D2 | **Messages are stubbed locally:** notification-service talks to providers through ports; locally a "dev mailbox" stores every SMS/WhatsApp/email and shows it in a small page (and to Playwright, which reads OTPs from it) | No real SMS locally; tests can complete OTP flows deterministically |
| D3 | **Assisted consent:** the grower may read the code to the retailer; the consent record notes `ASSISTED` and the retailer user | Real-world shop flow; still proves phone possession |
| D4 | **One repository per new service** (ADR-017), each with the repo kit; frontend in `growercare-frontend` (pnpm monorepo: `apps/grower-web`, `apps/staff-console`, `packages/*`) | Same governance everywhere |
| D5 | **No control-plane service in Phase 1:** the India country profile stays reference data in each service (as now); CFG-1 versioning/approvals move to Phase 2 | Keeps Phase 1 small; nothing in the journeys needs runtime profile changes |
| D6 | **Partner KYC (IAM-3) moves to Phase 2;** retailers in Phase 1 are seeded | Not on the exit-criteria path |
| D7 | **Warehouse (Snowflake) not local;** CDC stops at Kafka in Phase 1 | Snowflake has no local equivalent; the outbox/Debezium path is already proven |
| D8 | **Order of work:** GRW-03 consent/OTP → notification-service (needed by OTP) → GRW-02 farms + CASE-08 replicas → catalog-registry → CASE-07 phone search + overview → edge-gateway → frontend → Playwright journeys | Each step unblocks the next journey; UI starts once all APIs it needs exist |

## 10. Delivery plan
| Milestone | Content | Done when |
|---|---|---|
| M0 | This PRD agreed | — |
| M1 | GRW-03 consent + OTP; NOT-01 notification-service (stub providers, dev mailbox) | J1/J2 pass via API (smoke) |
| M2 | GRW-02 farms; catalog-registry CAT-01; CASE-08 (case uses farm and catalog replicas, seeds retired) | J3/J4 pass via API |
| M3 | CASE-07 phone search + territory overview; IAM-02 user directory; EDGE-01 gateway | J5/J6/J8/J9 pass via API through the gateway |
| M4 | growercare-frontend: grower-web + staff-console | All journeys clickable locally |
| M5 | Playwright journeys J1–J9 per persona, axe checks, smoke extended; a local "how to run everything" guide | Full local run green; exit criteria met |

## 11. Out of scope for Phase 1
Payments/claims/compensation (FIN-*), authenticity scans (AUTH-*), stewardship regulatory reporting beyond the case workflow, spray-service marketplace, field-pwa offline, label-compliance recommendations (GRW-4), recalls (GRW-6), DSAR workflow (PRV-2), data archive/purge (DATA-4+), Brazil as a second live country (data stays for isolation tests), AWS deployment.

## 12. Risks
| Risk | Mitigation |
|---|---|
| Laptop memory: 7+ JVMs + infrastructure + browser | Heap caps per service (≤ 384 MB), stack profiles (`infra`, `backend`, `all`), tests never run beside the full stack (lesson of 2026-11-17) |
| Seeds and replicas drifting | Every replicated master has an owner service and events; dev seeds share ids across services (as done for growers) |
| UI built before APIs settle | UI starts at M4 only; API contracts exported per service and the UI client generated from them |
