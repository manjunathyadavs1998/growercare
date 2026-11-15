# GrowerCare

A global grower and channel service platform: growers, retailers and staff raise and work **field cases** (complaints, advice, seed claims, adverse incidents) in regional cells.
- **Design:** modelled on a crop-science company, but not affiliated with or endorsed by one.
- **Scale target:** 10M users per country × 20 countries, growing to 1B.

[![backend-ci](https://github.com/manjunathyadavs1998/growercare/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/manjunathyadavs1998/growercare/actions/workflows/backend-ci.yml)

## Repository map
| Path | Contents |
|---|---|
| `docs/syngenta/` | Company and regulatory analysis, BRD, data platform, ID generation, comments design |
| `docs/architecture/` | 14 services, patterns, stack, scaling, AWS deployment, **ADR-001…014** |
| `docs/backend/01_RDS_SCHEMA.md` | Normalised schema analysis (IAM + 128 shard schemas) |
| `docs/specs/` | **Feature specs**: rules, examples, API, traceability (spec-driven development) |
| `gc-backend/` | Java 21 · Spring Boot 4.1 · Maven · PostgreSQL 17 · Redis · Keycloak. Contains `identity-access-service`, `case-service` and shared libraries |
| `gc-backend/contracts/` | Generated OpenAPI contracts (CI fails on drift) |
| `.claude/` | Claude Code project skills and agents |
| `.github/` | CI, PR policy, CodeQL, Dependabot, CODEOWNERS, templates, ruleset-as-code |

## Quick start
```bash
cd gc-backend/local-dev && cp .env.example .env && docker compose up -d --wait
cd .. && mvn -B verify                       # 336 tests (Docker needed for Testcontainers)
mvn -B package -DskipTests && local-dev/run-services.sh start && local-dev/smoke.sh
```

## Contributing
Read **[CONTRIBUTING.md](CONTRIBUTING.md)**:
- spec first;
- parameterized tests from the spec's examples;
- Conventional Commits;
- squash-merge PRs with green checks.

Security reports: [SECURITY.md](SECURITY.md).
