# SPEC-PLAT-06: Independent service repositories, versioned libraries, environment-only configuration
- **Requirements:** owner request (2026-11-16): separate services, network communication, configuration from environment/secrets; ADR-017
- **Status:** Implemented (2026-11-16; libs v1.0.0 released)
- **Owner:** platform (all repositories)

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| PLAT-R73 | any service repository, fresh clone | `mvn -B verify` with libs from GitHub Packages (or `mvn install` of the libs) | builds and tests **alone**: no parent reactor, no other service's code |
| PLAT-R74 | `growercare-platform-libs` | a tag `vX.Y.Z` is pushed | CI publishes every library jar + `gc-parent` + `gc-event-contracts` at version X.Y.Z to GitHub Packages |
| PLAT-R75 | a service | it needs another service | only over the network: HTTP(S) URL from an env var (`IAM_BASE_URL`), Kafka topics, the identity provider. No code or database dependency on another service |
| PLAT-R76 | a service | it starts with environment-specific variables missing | it **refuses to start** and logs **all** missing variable names in one message (never their values) |
| PLAT-R77 | `application.yml` of any service | — | contains no hostnames, URLs, usernames, passwords, secrets or bucket names: each is `${VAR}` without a default. `application-local.yml` with credentials does not exist |
| PLAT-R78 | a service repository | — | commits `.env.example` listing every variable the service reads, with a one-line description; `.env` is git-ignored |
| PLAT-R79 | a service repository | `mvn -B package jib:dockerBuild` (or CI on `main`) | produces an image on a distroless Java 21 base, running as non-root, whose only configuration input is the environment |
| PLAT-R80 | `growercare-local-platform` up + both service images on its network | `smoke.sh` | 0 failed checks (the same scenarios as before the split) |
| PLAT-R81 | any code repository | PR | the same gates as before: `verify` (tests, 80 % line coverage, contract drift), PR policy, Claude review (blocks on Critical/High), CodeQL. Each repository carries the **repo kit** from `growercare` (`scripts/apply-java-repo-kit.sh`); `main` is protected with no bypass |

## 2. Examples
| Example | Rule | Input | Expected |
|---|---|---|---|
| PLAT-E88 | R76 | case-service started with `DB_URL` and `IAM_BASE_URL` unset | exits non-zero; one log line names both variables |
| PLAT-E89 | R76 | all variables set | starts; no warning |
| PLAT-E90 | R77 | grep `application*.yml` of both services for `localhost`, `password:` with a literal, `secret:` with a literal | no matches |
| PLAT-E91 | R73 | clone `growercare-case-service` alone; `mvn -B verify` | green (libs resolved by version) |
| PLAT-E92 | R74 | tag `v1.0.0` on libs | packages visible in GitHub Packages at 1.0.0 |
| PLAT-E93 | R80 | local platform + both images from GHCR | `smoke.sh` 0 failures |

## 3. Traceability
| Rule / Example | Test |
|---|---|
| R76 / E88, E89 | `RequiredEnvironmentTest` (gc-common, parameterized) |
| R77 / E90 | `ConfigurationHygieneTest` in each service (parameterized over forbidden patterns) |
| R73, R81 / E91 | each service repository's CI |
| R74 / E92 | libs release workflow |
| R79 | service CI `image` job |
| R80 / E93 | `growercare-local-platform/smoke.sh` |
