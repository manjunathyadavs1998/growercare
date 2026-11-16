# GrowerCare

A global grower and channel service platform: growers, retailers and staff raise and work **field cases** (complaints, advice, seed claims, adverse incidents) in regional cells.
- **Design:** modelled on a crop-science company, but not affiliated with or endorsed by one.
- **Scale target:** 10M users per country × 20 countries, growing to 1B.

This repository holds the **architecture, decisions, cross-cutting specs, Claude skills/agents and the repo kit**. The code lives in one repository per service (ADR-017):

| Repository | Contents |
|---|---|
| [growercare-platform-libs](https://github.com/manjunathyadavs1998/growercare-platform-libs) | `gc-parent` build + shared libraries (security, errors, outbox/inbox, shard routing, event contracts, test support), versioned on GitHub Packages |
| [growercare-identity-access-service](https://github.com/manjunathyadavs1998/growercare-identity-access-service) | Users, partner orgs, scoped role grants, effective principals (port 8081) |
| [growercare-case-service](https://github.com/manjunathyadavs1998/growercare-case-service) | Sharded field cases, workflow, comments, sharing, SLAs, routing, attachments (port 8082) |
| [growercare-local-platform](https://github.com/manjunathyadavs1998/growercare-local-platform) | Local infrastructure (Postgres, Redis, Keycloak, Kafka + Debezium, SeaweedFS) and the end-to-end smoke test |

## This repository
| Path | Contents |
|---|---|
| `docs/syngenta/` | Company and regulatory analysis, BRD, data platform, ID generation, comments design |
| `docs/architecture/` | 14 services, patterns, stack, scaling, AWS deployment, **ADR-001…017** |
| `docs/specs/platform/` | Cross-cutting specs (quality gates, governance, AI review, events, errors, independent services). Service specs live in the service repositories |
| `templates/java-repo/` + `scripts/apply-java-repo-kit.sh` | **Repo kit**: CI, release/image workflows, PR policy, Claude review, CodeQL, ruleset, hooks, Claude skills. Every code repository gets the same gates |
| `.claude/` | Claude Code project skills and agents |
| `docs/ci-setup/` | CI/CD setup runbook (Markdown + PDF) |

## Quick start (local, end to end)
```bash
git clone https://github.com/manjunathyadavs1998/growercare-local-platform && cd growercare-local-platform
cp .env.example .env && docker compose up -d --wait
for s in identity-access-service case-service; do cp services/$s.env.example services/$s.env; done
docker compose -f compose.services.yml up -d       # released images from GHCR, configured only by env files
./debezium/register.sh && ./smoke.sh                # exit code = failed checks
```

## Contributing
Read **[CONTRIBUTING.md](CONTRIBUTING.md)**:
- spec first;
- parameterized tests from the spec's examples;
- Conventional Commits;
- squash-merge PRs with green checks.

Security reports: [SECURITY.md](SECURITY.md).
