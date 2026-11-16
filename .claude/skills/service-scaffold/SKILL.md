---
name: service-scaffold
description: Scaffold a new GrowerCare Spring Boot microservice (or a new module in an existing one) with hexagonal layout, policy-sdk authorization, shard routing, outbox/inbox, observability, Problem Details, Dockerfile, Helm values and CI workflow. Use when creating a service, adding a bounded-context module, or checking that a service follows the platform conventions.
---

# Service scaffold

Read first: `docs/architecture/03_TECH_STACK.md`, `04_REPOSITORIES.md`, and the service's doc in `docs/architecture/01_SERVICES/`.

## 1. Module & build
- Location: a new repository `growercare-<service-name>` (ADR-017). Use growercare-case-service as the reference, then run `scripts/apply-java-repo-kit.sh <repo-dir> service` from the growercare repository for CI, PR policy, review and hooks.
- Its POM inherits `com.growercare:gc-parent` at a released libs version. Dependency versions come only from the parent's `<dependencyManagement>` / the Boot BOM.
- Configuration is environment-only (SPEC-PLAT-06): no defaults for hosts, URLs, users, passwords, secrets or buckets; document every variable in `.env.example`; the service refuses to start, naming every missing one.
- Write `CLAUDE.md`, `docs/schema.md` and the specs under `docs/specs/<service>/` in the new repository.
- Java 21, Spring Boot 4.1.2, Spring MVC (`spring-boot-starter-webmvc`) with `spring.threads.virtual.enabled=true`. Use WebFlux only for gateway/realtime.
- Coding rules: load `java-backend-standards`.

## 2. Package layout (enforced by ArchUnit)
```
com.growercare.<service>/
├─ domain/            # records, value objects, domain events, state machines. NO Spring/SQL imports
├─ application/       # use cases, ports (in/out interfaces), transactions
├─ adapters/
│  ├─ in/web/         # controllers (generated interfaces from gc-contracts), DTO mapping (MapStruct)
│  ├─ in/kafka/       # consumers → inbox → use case
│  ├─ out/persistence/# JdbcClient repositories + named SQL + RowMappers (NO JPA, ADR-013; skill java-persistence-sql)
│  └─ out/kafka/      # (normally none: events go through the outbox table)
└─ config/
```
Add the standard ArchUnit test from gc-test-support (`HexagonalRules.check(<basePackage>)`).

## 3. Mandatory platform libraries
| Concern | Use | Never |
|---|---|---|
| Authorization | `gc-security`: provide one `PrincipalResolver` bean (IAM: DB; others: `CachingPrincipalResolver(IamPrincipalClient)`, see `CaseSecurityConfig`); `@RequiresPermission("case:assign")` on use cases; `PolicyEnforcer.require(...)` (404) and `ScopeFilter` in list queries (skill `java-security-rbac`) | Ad-hoc role checks in controllers |
| Shard routing | `gc-shard-routing`: `ShardRouter.forGrower(country, id)`, `forId(caseId)`, `forCaseNumber(CaseNumber)`; `ShardId.schemaName()` qualifies SQL | Accepting shard/country from client headers |
| Events | `outbox`: `outbox.append(event)` inside the same `@Transactional` use case; consumers extend `IdempotentConsumer` (inbox) | `KafkaTemplate.send` from business code |
| Errors | `gc-common` `GcException` hierarchy + RFC 9457 handler (skill `java-exception-handling`) | Custom error JSON |
| Observability | `observability`: OTel attributes `cell`, `country`; PII redaction in logs | Logging phone numbers, names, bodies |

## 4. API & IDs
- Implement the interfaces generated from `gc-contracts` (contract-first). If the contract doesn't exist, use the `api-contract` skill first.
- IDs are `long` internally and **serialized as strings** (`@JsonSerialize(using = ToStringSerializer.class)` via the shared Jackson module).
- List endpoints use cursor pagination; mutating endpoints accept `Idempotency-Key`; updates use `If-Match` versions.

## 5. Persistence
- Flyway SQL migrations, run as the `<db>_owner` role; the service connects as `<db>_app` (DML only).
  - Single schema: Boot Flyway (`spring.flyway.user`), as in identity-access-service.
  - Sharded: `ShardedFlywayMigrator` + `db/<svc>/{common,shard}`, as in case-service. See the `sharded-migration` skill.
- Schema design and queries: `java-persistence-sql`. Document new tables in `docs/backend/01_RDS_SCHEMA.md`.
- Every table that has data owned by a partner org enables RLS.
- Add `outbox` and `inbox` tables from the `outbox` lib migration templates.

## 6. Runtime config
- `application.yml` uses env vars only (12-factor). No secrets in the repo; secrets come from External Secrets → env.
- Actuator: expose `health`, `info`, `prometheus`; liveness has no dependencies, readiness checks DB + Kafka.

## 7. Delivery files
- Container: Jib or Buildpacks (no hand-written Dockerfile unless needed). If a Dockerfile is needed: multi-stage, distroless/`eclipse-temurin:21-jre` base, non-root user.
- `gc-deploy/charts/values/<service>/{dev,staging,prod-<cell>}.yaml` using the Helm library chart (requests/limits, HPA or KEDA, PDB, probes).
- CI: the reusable workflow `service-ci.yml` with `service: <name>` (affected-path filter).

## 8. Definition of done (checklist)
- [ ] A spec exists in `docs/specs/<service>/` and every example row has a parameterized test (skill `spec-driven-development`)
- [ ] `ArchitectureTest` added (parameterized over `HexagonalRules.rows()`, SPEC-PLAT-01 R4–R9) and green
- [ ] `<Svc>OpenApiContractIT` lists every endpoint of the spec and exports `contracts/<service>.openapi.json`; the export is committed
- [ ] springdoc is off by default and on in `application-local.yml`
- [ ] `local-dev/smoke.sh` extended with the service's happy path and an isolation check
- [ ] Use cases have unit tests; adapters have Testcontainers tests (see `test-strategy` skill)
- [ ] Authorization tests: allowed + denied + other-country + other-partner
- [ ] Events registered in `gc-contracts` with compatibility check
- [ ] Service doc in `docs/architecture/01_SERVICES/` matches the implementation
