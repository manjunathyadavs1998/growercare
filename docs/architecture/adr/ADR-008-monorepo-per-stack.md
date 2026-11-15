# ADR-008: Six repositories, with a monorepo per stack
- **Status:** Accepted · 2026-11-15 · *build tool superseded by ADR-014 (Maven)*
- **Context:** 14 services share libraries (policy, sharding, outbox, packs). Architecture rules must be enforced uniformly. Contracts are shared by backend and frontend.
- **Decision:** `gc-contracts`, `gc-backend` (Gradle multi-module), `gc-frontend` (pnpm + Turborepo), `gc-data-platform`, `gc-infra`, `gc-deploy`. Details: `04_REPOSITORIES.md`.
- **Consequences:**
  - Plus: atomic cross-service refactors, one BOM, ArchUnit everywhere.
  - Minus: CI must build only affected modules; CODEOWNERS required.
- **Alternatives:** a polyrepo per service (version drift, many PRs per change), a single repo for everything (mixed toolchains, noisy CI).
