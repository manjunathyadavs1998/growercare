# ADR-017: One repository per service, shared code as versioned libraries, configuration from the environment
- **Status:** Accepted · 2026-11-16
- **Supersedes:**
  - ADR-008 "monorepo per stack" for the backend;
  - the single-reactor part of ADR-014. Maven stays the build tool; each repository is now its own Maven build.
- **Context:**
  - With one repository and one Maven reactor, nothing can be cloned, built, versioned, released or deployed on its own: two services, five shared libraries, local infrastructure, contracts and governance all live together. The owner found this confusing and asked for independent services.
  - The services are already decoupled at runtime:
    - case-service calls IAM only over HTTPS (client-credentials token) and Kafka;
    - each service has its own database.
  - Configuration fell back to `localhost` defaults, and dev passwords were committed in `application-local.yml`. That doesn't meet the "configured only by the environment" expectation for production.
- **Decision:**

  | Repository | Owns | Releases |
  |---|---|---|
  | `growercare-platform-libs` | `gc-parent` (build rules), `gc-common`, `gc-security`, `gc-outbox`, `gc-shard-routing`, `gc-test-support`, `gc-event-contracts` (JSON Schemas) | Jars to **GitHub Packages**, semver, on tag `vX.Y.Z` |
  | `growercare-identity-access-service` | IAM code, migrations, OpenAPI contract, specs | Image `ghcr.io/<owner>/identity-access-service` |
  | `growercare-case-service` | case-service code, migrations, OpenAPI contract, specs | Image `ghcr.io/<owner>/case-service` |
  | `growercare-local-platform` | Local infrastructure (Postgres + per-service DB init, Redis, Keycloak realm, Kafka, Debezium Connect + connector configs, SeaweedFS), end-to-end `smoke.sh` | — |
  | `growercare` | Architecture, ADRs, cross-cutting specs, Claude skills/agents, and the **repo kit** (`templates/java-repo`: workflows, scripts, ruleset, hooks) that `scripts/apply-java-repo-kit.sh` copies into every code repository | — |

  - **History is preserved:** each repository is extracted with `git filter-repo`.
  - **Services depend on libraries by version.** A library change is a libs release plus a version-bump PR per service. A service never depends on another service's code.
  - **Configuration comes from the environment only:**
    - `application.yml` holds no environment-specific value (hosts, URLs, users, passwords, secrets, buckets). Each is `${VAR}` with no default; behaviour tunables (timeouts, pool sizes) may keep defaults.
    - A startup check in `gc-common` (`RequiredEnvironment`) fails fast and lists **every** missing variable at once.
    - Locally: a git-ignored `.env` documented by a committed `.env.example`.
    - AWS: Secrets Manager → External Secrets → Kubernetes Secret → `envFrom` (built with the deployment repo).
  - **Images:** built with Jib (no Dockerfile), on a distroless Java 21 base, running as non-root, configured only by environment variables.
- **Governance:** each repository carries its own copy of the kit, not cross-repository reusable workflows. The workflows need the governance scripts at runtime, and cross-repo SHA pins would have to be re-pinned in every repository after each kit change. Re-applying the kit brings a repository back in sync, and Dependabot keeps the pinned actions current in each one.
- **Consequences:**
  - Plus: each service is understood, built, tested, released and deployed alone; ownership is clear; and nothing secret is committed.
  - Minus: a shared-library change takes a release and bumps; there are five repositories to govern (rulesets, CI, secrets); GitHub Packages needs a token even for public packages (CI uses `GITHUB_TOKEN`; locally a PAT, or `mvn install` of the libs).
- **Alternatives:**
  - one repository with independent builds per folder: less administration, but still one clone and one history;
  - copying shared code into each service: security and error handling would drift;
  - JitPack instead of GitHub Packages: no authentication needed, but it depends on a third-party build service.
