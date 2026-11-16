# Contributing to GrowerCare

These rules are enforced by CI (`pr-policy`, `verify`, `claude-review`) and local git hooks. They are written down in
`docs/specs/platform/02-repository-governance.md` (SPEC-PLAT-02).

## 1. One-time setup
```bash
scripts/install-hooks.sh          # commit-msg (Conventional Commits) + pre-push (no direct pushes to main)
```
This repository holds docs, ADRs, platform specs, skills and the repo kit. Code lives in one repository per service
(ADR-017); each has its own `CONTRIBUTING.md`. To run the stack locally, start with
[growercare-local-platform](https://github.com/manjunathyadavs1998/growercare-local-platform) (see `CLAUDE.md` →
"Backend: how the pieces fit").

## 2. Workflow: spec first, always
1. **Spec:** add or update `docs/specs/<service>/<NN>-<feature>.md`, with numbered rules, an example table, the API and the errors (skill `spec-driven-development`). New features get the spec agreed before code.
2. **Branch:** `git switch -c <type>/<short-name>`, e.g. `feat/sla-timers`, `fix/stale-etag`.
3. **Tests first:** write `@ParameterizedTest` rows from the spec examples, named by spec ID (`Arguments.argumentSet("CASE-E52 …", …)`), and see them fail.
4. **Implement** until green. Follow the skills `java-backend-standards`, `java-persistence-sql`, `java-security-rbac` and `java-exception-handling`.
5. **Verify:** in a service repository, run `mvn -B verify` (and the platform's `smoke.sh` if endpoints or security changed); commit any regenerated `contracts/*.openapi.json`. Here, the `verify` check runs the kit checks.
6. **Pull request:** fill in the template. The title becomes the squash commit, and the body must name the spec (`Spec: docs/specs/…`).
7. **Merge:** squash only, once all required checks are green and all conversations are resolved. The branch is deleted automatically.

## 3. Commit and PR titles: Conventional Commits
`<type>(<scope>)?: <subject>`
- **Types:** `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`, `perf`, `revert`.
- **Breaking changes:** `feat(case)!: …` plus a `BREAKING CHANGE:` footer.
- **Scopes:** `iam`, `case`, `security`, `shard`, `common`, `outbox`, `local-dev`, `deps`, `docs`, `repo`.

## 4. Required checks on `main`
| Check | What it proves |
|---|---|
| `backend-ci / verify` | All tests (unit, Testcontainers, ArchUnit, OpenAPI) green; no API-contract drift; **merged line coverage ≥ 80%** (sticky PR comment with per-module numbers); SonarQube Cloud quality gate (≥ 80% coverage on new code, no new bugs/vulnerabilities) once `SONAR_TOKEN` is set |
| `pr-policy / policy` | Title, spec reference, tests with code, branch name; self-tests of all gate scripts |
| `claude-review` | Claude Code reviews the diff against `CLAUDE.md`, the spec and the security checklist. It posts inline comments, a severity table and `review:*` labels. **Any Critical/High finding fails the check and blocks the merge.** Medium/Low are advisory |
| `codeql` | Static security analysis and dependency review |

**When `claude-review` blocks your PR:**
- **Real issue:** fix it, push, and the review re-runs.
- **False positive:** reply on the inline comment with the evidence and push a commit that makes the intent clear (a test row or comment). The check re-evaluates the new diff. Never weaken the gate.

## 5. Never
- Push to `main` directly or force-push shared branches.
- Commit secrets, real personal data, `.env` files or the take-home PDF.
- Change an applied migration, or an ADR's decision without a superseding ADR.
- Claim tests pass without running them.
