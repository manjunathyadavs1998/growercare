> **Superseded in part by ADR-017 (2026-11-16).** The backend is no longer one monorepo: each service has its own repository, shared code is released as versioned libraries (`growercare-platform-libs`), and local infrastructure lives in `growercare-local-platform`. The rest of this document (frontend, data platform, infra, deploy repositories) still applies.

# Git Repositories (6)

**Strategy:** one monorepo per technology stack, plus separate repos for contracts, data platform, infrastructure and deployment state (ADR-008).

| Repo | Owner | Contents | CI |
|---|---|---|---|
| `gc-contracts` | Architecture guild | OpenAPI 3.1 specs per service, Avro schemas, shared error/pagination schemas, compatibility rules | Spectral lint, breaking-change check (openapi-diff), Avro compatibility vs Schema Registry, publish generated artifacts (Java stubs to GitHub Packages, TS client to npm) |
| `gc-backend` | Service teams (CODEOWNERS per service) | Maven multi-module (ADR-014): 14 services + shared libs | Affected-module build/test, Testcontainers, JaCoCo gate, CodeQL, Trivy, SBOM, cosign, push to ECR, PR to `gc-deploy` |
| `gc-frontend` | Frontend teams | pnpm + Turborepo: apps + shared packages; Playwright E2E | Lint, typecheck, Vitest, Playwright, bundle-size budget, Lighthouse CI, upload to S3 + CloudFront invalidation per env |
| `gc-data-platform` | Data platform team | Debezium & Snowflake connector configs, Snowflake SQL migrations (schemachange), Dynamic Tables, policies, reconciliation jobs, dbt models for analytics | SQL lint, schemachange dry-run, connector config validation |
| `gc-infra` | Platform/SRE | Terraform modules + Terragrunt live config per account/region/cell (VPC, EKS, RDS/Aurora, MSK, ElastiCache, S3, IAM, KMS) | `terraform fmt/validate`, tflint, checkov/tfsec, plan on PR, apply on approval |
| `gc-deploy` | Platform/SRE (service teams PR their values) | Helm library chart + per-service values per env/cell; Argo CD ApplicationSets; Argo Rollouts canary strategies | Helm lint, kubeconform, policy checks (Kyverno CLI); Argo CD syncs from `main` |

## `gc-backend` layout
```
gc-backend/
├─ pom.xml                     # parent POM: Boot BOM, enforcer (no ORM, ADR-013), surefire/failsafe, JaCoCo (ADR-014)
├─ platform-bom/               # versions
├─ libs/
│  ├─ policy-sdk/              # ABAC enforcement, grants cache
│  ├─ shard-routing/           # country registry, grower→schema, ID/case_no decode, ShardedFlywayMigrator (ADR-013: no Hibernate)
│  ├─ outbox/                  # outbox writer, inbox (idempotent consumer)
│  ├─ compliance-pack-api/     # SPI for country packs
│  ├─ packs/pack-in, pack-br, pack-us, pack-eu
│  ├─ observability/           # OTel, logging (PII redaction), metrics conventions
│  ├─ problem-details/         # RFC 9457 error mapping
│  └─ test-support/            # Testcontainers fixtures, shard test DB, Kafka helpers
├─ services/
│  ├─ edge-gateway/
│  ├─ identity-access-service/
│  ├─ grower-service/
│  ├─ catalog-registry-service/
│  ├─ case-service/            # boot apps: case-api, comment-api
│  ├─ field-service/  authenticity-service/  claims-finance-service/
│  ├─ notification-service/  realtime-gateway/  media-service/
│  ├─ integration-service/  data-lifecycle-service/  control-plane-service/
├─ perf/                       # k6 scripts, chaos experiments
└─ .github/workflows/
```
Each service follows the hexagonal package layout:
`domain/` (no framework imports), `application/` (use cases, ports), `adapters/in/web`, `adapters/in/kafka`, `adapters/out/persistence`, `adapters/out/kafka`, `config/`.

## `gc-frontend` layout
```
gc-frontend/
├─ apps/  grower-web/  staff-console/  field-pwa/
├─ packages/  ui-kit/  api-client/ (orval-generated)  i18n/  auth/  policy/  utils/  config/
├─ e2e/                        # Playwright
└─ turbo.json, pnpm-workspace.yaml
```

## Why not one repo per service (polyrepo)?
| Polyrepo | Monorepo per stack (chosen) |
|---|---|
| Strong isolation, simple per-repo CI | Atomic refactors across services + shared libs |
| Library version drift, many PRs for one change | One BOM, one toolchain, consistent conventions |
| Harder to enforce architecture rules | ArchUnit and convention plugins apply everywhere |

Costs of the monorepo: CI must build only affected modules (`mvn -pl <module> -am` + path filters), and CODEOWNERS keeps team ownership clear.
