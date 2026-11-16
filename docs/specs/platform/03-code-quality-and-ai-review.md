# SPEC-PLAT-03: Code analysis, 80% coverage gate, Claude Code review with severity gate

> **Paths after ADR-017 (2026-11-16).** This document was written for the `gc-backend` monorepo. The rules still hold; the paths moved:
> `gc-backend/services/<svc>` → repository `growercare-<svc>` · `gc-backend/contracts/<svc>.openapi.json` → `contracts/` in that repository · `gc-backend/contracts/events/` → `gc-event-contracts` in growercare-platform-libs · `gc-backend/local-dev/` (incl. `smoke.sh`) → growercare-local-platform · `gc-backend/coverage` → per repository (services `target/site/jacoco-merged/jacoco.xml`, libs `coverage/target/site/jacoco-aggregate/jacoco.xml`) · workflow `backend-ci` → the kit's `ci.yml` (check `verify`). See SPEC-PLAT-06.

- **Requirements:** NFR maintainability and security; SPEC-PLAT-02 (governance)
- **Status:** Implemented. Activation steps that need the owner: D3 (`/install-github-app`) and D4 (SonarQube Cloud token)
- **Owner:** repository / gc-backend

## 0. Decisions (agreed 2026-11-15)
| # | Decision |
|---|---|
| D1 | **Coverage is measured as one merged JaCoCo report**: unit and integration test data of **all** modules aggregated by a `coverage` module. A library's code exercised by service ITs counts, and `*Application` bootstrap classes are excluded. The **hard gate is overall line coverage ≥ 80%**, enforced in `backend-ci / verify`. Branch coverage and per-module numbers are reported but not gated |
| D2 | **SonarQube Cloud** (free for public repos) adds a quality gate. The default "Sonar way" requires **≥ 80% coverage on new code**, no new bugs or vulnerabilities, reviewed security hotspots and ≤ 3% duplication. It decorates PRs. Analysis runs in CI with the merged JaCoCo XML (`sonar.qualitygate.wait=true` fails the job when the gate fails) |
| D3 | **Claude Code reviews every PR** (`anthropics/claude-code-action`, SHA-pinned). Claude posts inline comments and returns **structured findings** (`--json-schema`). A deterministic, self-tested script (`scripts/review_gate.py`), not the model, turns them into a severity summary, labels and the check result. Auth is a repository secret `CLAUDE_CODE_OAUTH_TOKEN` created with `claude setup-token` from **any** Claude subscription account (it doesn't have to match the GitHub account's email), or alternatively an `ANTHROPIC_API_KEY` secret. Comments are posted with the workflow's `github.token`, so the Claude GitHub App is not required |
| D4 | **Blocking:** the `claude-review` check fails on any **Critical or High** finding and is a **required check** on `main`. Medium/Low are flagged but don't block. A false positive is resolved by a follow-up commit (the review re-runs) with the justification in the PR conversation |
| D5 | **Fork PRs** don't receive secrets: `claude-review` fails with "maintainer review required", and a maintainer brings the change in through an internal branch. Secrets are never exposed to fork code (no `pull_request_target`) |

## 1. Rules
| Rule | Given | When | Then |
|---|---|---|---|
| PLAT-R40 | `mvn verify` | build finishes | `gc-backend/coverage/target/site/jacoco-aggregate/jacoco.xml` + HTML exist, covering every production module |
| PLAT-R41 | merged report | overall LINE coverage < 80.0% | `backend-ci / verify` fails with the measured value |
| PLAT-R42 | a PR | `verify` ran | a sticky PR comment and the job summary show overall and per-module line/branch coverage against the threshold |
| PLAT-R43 | `SONAR_TOKEN` secret exists | push to main / PR | SonarQube Cloud analysis runs in `verify`; a failed quality gate fails the check. Without the token the step is skipped with a notice |
| PLAT-R44 | a same-repo PR is opened, updated or marked ready | — | Claude reviews the diff against the project rules (`CLAUDE.md` conventions, spec-driven development, security skill checklist) and posts inline comments for concrete issues |
| PLAT-R45 | Claude's structured output | gate script runs | one finding = `{severity ∈ Critical/High/Medium/Low, file, line, title, detail, rule}`; a summary table is posted (sticky), each finding becomes a check annotation, and labels `review:critical` / `review:high` / `review:medium` / `review:low` / `review:clean` are set |
| PLAT-R46 | ≥ 1 Critical or High finding | — | `claude-review` fails (required check ⇒ PR blocked) |
| PLAT-R47 | only Medium/Low, or none | — | `claude-review` passes |
| PLAT-R48 | malformed or missing structured output (model or tool failure) | — | `claude-review` **fails closed** with "review incomplete", never passes silently |
| PLAT-R49 | a fork PR | — | `claude-review` fails with "maintainer review required" (D5) |

**Severity rubric** (given to Claude, documented here):
| Severity | Meaning (examples) |
|---|---|
| Critical | Exploitable security flaw or data loss: auth bypass, cross-tenant/country data exposure, SQL injection, secret committed, destructive migration without contract step |
| High | A spec violation that produces wrong behaviour in production: broken authorization rule (403/404 semantics), missing transaction/outbox atomicity, race without guard, unhandled error path → 500, PII in logs or events |
| Medium | Maintainability or robustness: missing parameterized test rows for a spec example, convention breaks (ORM, field injection), weak validation, N+1 queries |
| Low | Style, naming, docs, minor simplifications |

## 2. Examples (`scripts/review_gate.py --selftest`; coverage gate `scripts/coverage_gate.py --selftest`)
| Example | Input | Expected |
|---|---|---|
| PLAT-E50 | findings `[]` | pass, label `review:clean` |
| PLAT-E51 | 1 Low + 2 Medium | pass, labels `review:medium`, `review:low` |
| PLAT-E52 | 1 High | fail, label `review:high` |
| PLAT-E53 | 1 Critical + 1 Low | fail, labels `review:critical`, `review:low` |
| PLAT-E54 | output empty / not JSON / missing `findings` | fail "review incomplete" |
| PLAT-E55 | finding with unknown severity `"Blocker"` | fail "review incomplete" (schema violation) |
| PLAT-E56 | coverage XML with line 80.0% | pass |
| PLAT-E57 | coverage XML with line 79.9% | fail |
| PLAT-E58 | coverage XML missing | fail "no coverage report" |

## 3. Traceability
| Rule | Artefact |
|---|---|
| R40 | `gc-backend/coverage/pom.xml` (jacoco `report-aggregate`) |
| R41, R42, E56–E58 | `scripts/coverage_gate.py` (+ `--selftest`), `backend-ci.yml` steps "Coverage gate" / "Coverage comment" |
| R43 | `backend-ci.yml` step "SonarQube Cloud analysis" in job `verify` (reuses the build and the merged report), `sonar.*` properties in `gc-backend/pom.xml` |
| R44–R49, E50–E55 | `.github/workflows/claude-review.yml`, `scripts/review_gate.py` (+ `--selftest`, run in `pr-policy`) |
| D4 | `.github/rulesets/main.json` required checks `verify`, `policy`, `claude-review` |
