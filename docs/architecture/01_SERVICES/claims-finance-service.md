# claims-finance-service
**Context:** Claims, compensation & channel finance · **Phase:** 2 · **Tech:** Spring Boot, Postgres (not sharded; partitioned by country), Kafka, saga orchestration

## Responsibilities
- Compensation proposals from case outcomes: replacement, replant seed, retailer credit note, payout (FIN-1).
- **Maker–checker** with role/country **approval limits in local currency**, multi-level above thresholds, SoD (FIN-2, ACC-6, ACC-7).
- **Saga to ERP** via integration-service: create credit note / order / payment request, receive settlement, compensate on failure (FIN-3).
- Channel scheme/rebate claims and disputes (FIN-4).
- Money in minor units + ISO-4217; FX only for reporting (FIN-6).
- Finance dashboards feed (FIN-7, via Snowflake).

## Component diagram
```mermaid
flowchart LR
  k1[(Kafka case.root_cause_set)] --> prop[Compensation proposer]
  api[REST API<br/>proposals, approvals] --> app[Application layer]
  prop --> app
  app --> appr[Approval engine<br/>limits matrix, SoD, levels]
  app --> saga[Settlement saga orchestrator<br/>state persisted]
  appr --> db[(Postgres claims)]
  saga --> db
  saga --> ob[Outbox] --> db
  db -. Debezium .-> k2[(Kafka claims.*)]
  k2 --> is[integration-service<br/>ERP ACL]
  is --> k3[(Kafka integration.erp.*)]
  k3 --> saga
```

## Key flow — approval and ERP settlement saga
```mermaid
sequenceDiagram
  participant FA as Finance analyst (maker)
  participant FM as Finance approver (checker)
  participant C as claims-finance-service
  participant I as integration-service
  participant E as ERP
  FA->>C: POST /claims {caseId, type: CREDIT_NOTE, amountMinor: 1250000, currency: INR}
  C->>C: limits matrix: amount > maker limit -> requires checker
  FM->>C: POST /claims/{id}/approve
  C->>C: SoD: approver != maker, scope covers IN
  C->>C: saga state APPROVED -> ERP_REQUESTED (outbox claims.settlement.requested)
  C--)I: claims.settlement.requested (idempotency key = claimId)
  I->>E: create credit note
  E-->>I: doc no. 9100023
  I--)C: integration.erp.credit_note.created
  C->>C: saga -> SETTLED (outbox claims.settled)
  Note over C,I: on ERP rejection -> saga COMPENSATE: reverse approval, notify finance
```

## API (selected)
`POST /claims`, `GET /claims?country=&status=`, `POST /claims/{id}/approve`, `POST /claims/{id}/reject`, `GET /approval-limits`, `POST /schemes/claims` (phase 3).

## Data
`claims`, `claim_lines`, `approvals`, `approval_limits` (effective-dated per country/role), `saga_instances`, `outbox`, `inbox`.

## Events
- **Publishes:** `claims.proposed`, `claims.approved`, `claims.settlement.requested`, `claims.settled`, `claims.failed`.
- **Consumes:** `case.root_cause_set`, `integration.erp.*`, `iam.grant.revoked`.

## Scaling profile
Low volume (compensation applies to a small % of cases) but **high correctness**. Single partitioned Postgres per cell; no sharding needed.

## Limitations
- The ERP is the system of record. Our status can lag ERP by minutes; daily reconciliation compares saga states with ERP documents.
- Tax and legal invoice numbering are delegated to ERP / e-invoicing; this service never issues tax documents.

## Trade-offs
| Choice | Alternative | Why |
|---|---|---|
| Orchestrated saga (persisted state machine) | Choreography | Money flows need one place that knows the state and can compensate. Costs: a central orchestrator. |
| In-house saga | Temporal | Few step types today. Temporal is worth adopting if sagas multiply (payouts, rebates, barter). |
