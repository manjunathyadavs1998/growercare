# ADR-005: Keycloak (self-hosted per cell) for identity
- **Status:** Accepted · 2026-11-15
- **Context:** Hundreds of millions of grower accounts with mobile OTP, staff SSO federation with MFA, partner logins, per-cell data residency, custom flows (OTP abuse controls, assisted onboarding).
- **Decision:** Keycloak per cell, with two realms (`growers`, `workforce`). Grower OTP is implemented via a custom authenticator calling identity-access-service.
- **Consequences:**
  - Plus: no per-MAU licence; full flow control; data stays in the cell.
  - Minus: we operate and tune it (DB-backed users, Infinispan, upgrades).
- **Alternatives:** Amazon Cognito (per-MAU cost at this scale, limited custom flows), Auth0/Okta CIC (cost, residency).
