> **Superseded in part by ADR-017 (2026-11-16).** The backend is no longer one monorepo: each service has its own repository, shared code is released as versioned libraries (`growercare-platform-libs`), and local infrastructure lives in `growercare-local-platform`. The rest of this document (frontend, data platform, infra, deploy repositories) still applies.

# Git Repositories

**Strategy:** one repository per backend service plus a shared-libraries repository (ADR-017, superseding the backend part of ADR-008); one monorepo for the frontend; separate repos for contracts, data platform, infrastructure and deployment state.

| Repo | Owner | Contents | CI |
|---|---|---|---|
| `gc-contracts` | Architecture guild | OpenAPI 3.1 specs per service, Avro schemas, shared error/pagination schemas, compatibility rules | Spectral lint, breaking-change check (openapi-diff), Avro compatibility vs Schema Registry, publish generated artifacts (Java stubs to GitHub Packages, TS client to npm) |
| `growercare-platform-libs` | Platform team | `gc-parent` + shared libraries (`gc-common`, `gc-security`, `gc-shard-routing`, `gc-outbox`, `gc-event-contracts`, `gc-test-support`), released by tag to GitHub Packages | Repo kit (ADR-017): `verify` (tests + coverage ≥ 80%), `pr-policy`, `claude-review`, CodeQL; `release` on tag |
| `growercare-<service>` (one per service, ADR-017) | Owning service team | One Spring Boot service: code, `contracts/`, `docs/schema.md`, `docs/specs/<service>/`, `.env.example` | Repo kit: `verify` (tests + contract drift + coverage ≥ 80%), `pr-policy`, `claude-review`, CodeQL; image to GHCR (ECR on AWS) |
| `growercare-local-platform` | Platform team | Local infrastructure (Postgres, Redis, Keycloak, Kafka + Debezium, SeaweedFS), `compose.services.yml`, end-to-end `smoke.sh` | `verify`, `pr-policy`, `claude-review` |
| `gc-frontend` | Frontend teams | pnpm + Turborepo: apps + shared packages; Playwright E2E | Lint, typecheck, Vitest, Playwright, bundle-size budget, Lighthouse CI, upload to S3 + CloudFront invalidation per env |
| `gc-data-platform` | Data platform team | Debezium & Snowflake connector configs, Snowflake SQL migrations (schemachange), Dynamic Tables, policies, reconciliation jobs, dbt models for analytics | SQL lint, schemachange dry-run, connector config validation |
| `gc-infra` | Platform/SRE | Terraform modules + Terragrunt live config per account/region/cell (VPC, EKS, RDS/Aurora, MSK, ElastiCache, S3, IAM, KMS) | `terraform fmt/validate`, tflint, checkov/tfsec, plan on PR, apply on approval |
| `gc-deploy` | Platform/SRE (service teams PR their values) | Helm library chart + per-service values per env/cell; Argo CD ApplicationSets; Argo Rollouts canary strategies | Helm lint, kubeconform, policy checks (Kyverno CLI); Argo CD syncs from `main` |

## Backend repositories (ADR-017)
Each service repository follows the hexagonal package layout:
`domain/` (no framework imports), `application/` (use cases, ports), `adapters/in/web`, `adapters/in/kafka`, `adapters/out/persistence`, `adapters/out/kafka`, `config/`.

Services depend on **released** `growercare-platform-libs` versions, talk to each other only over the network (HTTPS, Kafka) and are configured from environment variables only (SPEC-PLAT-06). The governance files (CI, PR policy, Claude review, CodeQL, ruleset, hooks) come from the repo kit in this repository: `scripts/apply-java-repo-kit.sh <repo-dir> lib|service`.

Planned service repositories follow the service list in `01_SERVICES/` (edge-gateway, grower-service, catalog-registry-service, field-service, notification-service, data-lifecycle-service, …).

## `gc-frontend` layout
```
gc-frontend/
├─ apps/  grower-web/  staff-console/  field-pwa/
├─ packages/  ui-kit/  api-client/ (orval-generated)  i18n/  auth/  policy/  utils/  config/
├─ e2e/                        # Playwright
└─ turbo.json, pnpm-workspace.yaml
```

## Why ADR-008 chose a monorepo per stack (history; the backend part is superseded by ADR-017)
| Polyrepo | Monorepo per stack (chosen) |
|---|---|
| Strong isolation, simple per-repo CI | Atomic refactors across services + shared libs |
| Library version drift, many PRs for one change | One BOM, one toolchain, consistent conventions |
| Harder to enforce architecture rules | ArchUnit and convention plugins apply everywhere |

Costs of the monorepo: CI must build only affected modules (`mvn -pl <module> -am` + path filters), and CODEOWNERS keeps team ownership clear.
