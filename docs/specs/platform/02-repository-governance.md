# SPEC-PLAT-02: Repository governance (GitHub)
- **Requirements:** NFR maintainability and security; conventions 9–12 in `CLAUDE.md` (tests before claims, ADR changes, spec-driven, parameterized)
- **Status:** Implemented. R1 (server-side enforcement) is pending a plan change, see D1
- **Repository:** `manjunathyadavs1998/growercare` (private)

## 0. Decisions
| # | Decision | Why |
|---|---|---|
| D1 | `main` is protected by a **repository ruleset** kept as code in `.github/rulesets/main.json`, applied by `scripts/apply-repo-settings.sh`. **GitHub Free does not allow rulesets or branch protection on private repos** (API 403, verified 2026-11-15). Until the repo is on GitHub Pro (or public), the same rules are enforced by CI checks + local git hooks, which are visible but bypassable | Rules as reviewable code; one command to switch on |
| D2 | **Squash merge only**; the PR title becomes the commit message (Conventional Commits); head branches are deleted after merge; auto-merge allowed | Linear, readable history; one commit per spec change |
| D3 | Required approvals = **0** while there is a single maintainer (GitHub doesn't let authors approve their own PRs). Raise to 1 + code-owner review when a second maintainer joins | Avoid locking the only maintainer out |
| D4 | CodeQL and dependency review run **only when the repository is public** (they need GitHub Advanced Security on private repos). Dependabot alerts and security updates are on for all plans | Use what the plan offers; no permanently red checks |
| D5 | Third-party actions are **pinned to full commit SHAs** (with the version in a comment) and updated by Dependabot | Supply-chain safety (a moved tag cannot inject code) |

## 1. Rules
| Rule | Given | When | Then |
|---|---|---|---|
| PLAT-R20 | `main` | someone pushes directly, force-pushes or deletes it | rejected by the ruleset (D1). Meanwhile the `pre-push` hook rejects it locally |
| PLAT-R21 | a pull request into `main` | merge | requires green `backend-ci / verify` and `pr-policy / policy`, branch up to date, all conversations resolved, linear history |
| PLAT-R22 | PR title | not `type(scope)?: subject` (types: feat, fix, docs, refactor, test, build, ci, chore, perf, revert; subject 3–72 chars) | `pr-policy` fails |
| PLAT-R23 | PR body | has no `Spec:` line naming `docs/specs/…` (or `Spec: n/a – <reason>`) | `pr-policy` fails (spec-driven development) |
| PLAT-R24 | PR changes production code (`gc-backend/**/src/main/**`) | no change under `gc-backend/**/src/test/**` and no `Tests: n/a – <reason>` line | `pr-policy` fails (no code without tests) |
| PLAT-R25 | PR changes a migration (`**/db/**/*.sql`) | `docs/backend/01_RDS_SCHEMA.md` not changed | `pr-policy` warns (schema doc must stay true) |
| PLAT-R26 | head branch | not `type/short-name` (same types, lower-case) | `pr-policy` fails |
| PLAT-R27 | any file path | changed | GitHub requests review from the owner in `.github/CODEOWNERS` |
| PLAT-R28 | weekly | dependencies (Maven, GitHub Actions, Docker Compose images) | Dependabot opens grouped PRs, labelled, with Conventional-Commit titles |
| PLAT-R29 | local clone | `scripts/install-hooks.sh` | `commit-msg` enforces Conventional Commits; `pre-push` blocks pushes to `main` |
| PLAT-R30 | a vulnerability is suspected | — | reported privately as described in `SECURITY.md`, never in a public issue |

## 2. Examples (`pr-policy` checks run as table-driven cases in `scripts/pr-policy.sh`, self-tested by `scripts/pr-policy-selftest.sh`)
| Example | Input | Expected |
|---|---|---|
| PLAT-E30 | title `feat(case): add SLA timers` | pass |
| PLAT-E31 | title `Added stuff` | fail R22 |
| PLAT-E32 | title `fix: x` (subject < 3 chars) | fail R22 |
| PLAT-E33 | body `Spec: docs/specs/case/04-sla-timers.md` | pass |
| PLAT-E34 | body `Spec: n/a – dependency bump` | pass |
| PLAT-E35 | body without a `Spec:` line | fail R23 |
| PLAT-E36 | files `…/src/main/X.java` only | fail R24 |
| PLAT-E37 | files `…/src/main/X.java` + `…/src/test/XTest.java` | pass |
| PLAT-E38 | files `…/src/main/X.java`, body `Tests: n/a – log message only` | pass |
| PLAT-E39 | branch `feat/sla-timers` · `my-branch` | pass · fail R26 |
| PLAT-E40 | branch `dependabot/maven/…` | pass (bot branches exempt) |

## 3. Traceability
| Rule | Artefact |
|---|---|
| R20, R21 | `.github/rulesets/main.json`, `scripts/apply-repo-settings.sh`; meanwhile `.githooks/pre-push` |
| R22–R26 | `.github/workflows/pr-policy.yml` → `scripts/pr-policy.sh`; examples E30–E40 in `scripts/pr-policy-selftest.sh` (runs in CI) |
| R27 | `.github/CODEOWNERS` |
| R28 | `.github/dependabot.yml` |
| R29 | `.githooks/`, `scripts/install-hooks.sh` |
| R30 | `SECURITY.md` |
| D2 | repo settings applied by `scripts/apply-repo-settings.sh` |
| D4 | `.github/workflows/codeql.yml` (`if: !github.event.repository.private`) |
