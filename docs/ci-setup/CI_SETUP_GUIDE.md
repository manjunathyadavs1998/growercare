# GrowerCare: CI/CD setup with GitHub Actions
### Runbook: what was set up, in which order, with the exact commands · v1.0 · 2026-11-15

> **Paths after ADR-017 (2026-11-16).** This document was written for the `gc-backend` monorepo. The rules still hold; the paths moved:
> `gc-backend/services/<svc>` → repository `growercare-<svc>` · `gc-backend/contracts/<svc>.openapi.json` → `contracts/` in that repository · `gc-backend/contracts/events/` → `gc-event-contracts` in growercare-platform-libs · `gc-backend/local-dev/` (incl. `smoke.sh`) → growercare-local-platform · `gc-backend/coverage` → per repository (services `target/site/jacoco-merged/jacoco.xml`, libs `coverage/target/site/jacoco-aggregate/jacoco.xml`) · workflow `backend-ci` → the kit's `ci.yml` (check `verify`). See SPEC-PLAT-06.

> **Purpose:** a reference for maintaining or rebuilding the repository's CI, quality gates and branch protection.
> **Specs:** `docs/specs/platform/01-quality-gates-and-smoke.md` (SPEC-PLAT-01), `02-repository-governance.md` (SPEC-PLAT-02), `03-code-quality-and-ai-review.md` (SPEC-PLAT-03).
> **Repository:** `github.com/manjunathyadavs1998/growercare` (public; `main` locked: PR only, no bypass).

---

## 1. The result at a glance

| Layer | What runs | Blocks merge? |
|---|---|---|
| `backend-ci / verify` | `mvn verify`: unit + Testcontainers integration + ArchUnit + OpenAPI contract tests (336 tests); API contract drift check; **merged line coverage ≥ 80%** (currently ~91%); SonarQube Cloud (optional, off for now) | Yes (required) |
| `pr-policy / policy` | Conventional-Commit PR title, `Spec:` line in the PR body, tests with production code, branch name; self-tests of every gate script | Yes (required) |
| `claude-review` | Claude Code reviews the diff with inline comments; a deterministic gate posts a severity table and `review:*` labels and **fails on Critical/High** | Yes (required, once the ruleset update in §10.6 is applied) |
| `codeql` | CodeQL (Java, Actions) + dependency review (public repos) | Advisory |
| Ruleset `main-protection` | No direct push, no force-push, no deletion of `main`; PR + green required checks + resolved conversations + squash merge; **no bypass, not even the owner** | Enforced by GitHub |
| Local hooks | `commit-msg` (Conventional Commits), `pre-push` (no pushes to `main`) | Client side |

```
branch <type>/<name> ─► PR ─┬─► backend-ci / verify ─┐
                            ├─► pr-policy / policy ──┼─► all green + threads resolved
                            ├─► claude-review ───────┘          │
                            └─► codeql (advisory)                ▼
                                                     squash merge ─► main
```

---

## 2. Prerequisites (one time per machine)

| Tool | Version used | Notes |
|---|---|---|
| Git | 2.54 | `git config --global user.name/email` set |
| GitHub CLI `gh` | 2.104 | Installed with winget (§4). Path: `C:\Program Files\GitHub CLI\gh.exe` |
| JDK | 21 (Temurin/Oracle) | Same version as CI |
| Maven | 3.9.x | Same as CI |
| Docker Desktop | 29.x | Needed for Testcontainers. CLI at `%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin` (not on PATH by default) |
| Python | 3.x | Gate scripts and this PDF build |
| Claude Code | latest | `claude setup-token` for the review token (§10) |

Accounts:
- a GitHub account (owner of the repository);
- a Claude subscription. It **may use a different email** than the GitHub account (§10.2).

---

## 3. Step 1: prepare the local repository

1. **Ignore what must never be committed** (`.gitignore` at the repo root):
   - `take-home-assignment.pdf`;
   - `**/target/`;
   - `gc-backend/local-dev/.env`;
   - `gc-backend/local-dev/logs/`;
   - IDE files;
   - `.claude/settings.local.json`.
2. **Normalise line endings** (`.gitattributes`): `* text=auto eol=lf`, plus explicit `eol=lf` for `*.sh *.sql *.yml *.json *.java *.md`.
   - Shell scripts must stay LF: Docker runs `01-init.sh` inside Linux.
   - Flyway checksums must be stable across operating systems.
3. **Initialise on `main`:**
   ```bash
   git init -b main
   git add -A
   git diff --cached --name-only | grep -iE '\.pdf|\.env$|target/|\.log$' || echo "nothing excluded is staged"
   git commit -m "..."
   ```
4. **Privacy before anything is public.** Use the GitHub no-reply address for commits in this repository:
   ```bash
   gh api user --jq '"\(.id)+\(.login)@users.noreply.github.com"'
   git config user.email "<id>+<login>@users.noreply.github.com"
   git config user.name  "<login>"
   ```
   Also scan tracked files for personal paths or emails: `git grep -nIE "@gmail\.com|C:/Users/" -- .`

---

## 4. Step 2: install and authenticate the GitHub CLI

```powershell
winget install --id GitHub.cli -e --source winget --accept-package-agreements --accept-source-agreements
& "C:\Program Files\GitHub CLI\gh.exe" auth login        # GitHub.com → HTTPS → login with browser
& "C:\Program Files\GitHub CLI\gh.exe" auth status        # scopes: repo, workflow (+ gist, read:org)
```
- `delete_repo` was added later, only to recreate the repository (§6): `gh auth refresh -h github.com -s delete_repo`.
- Interactive logins are run **by the user** in a terminal; the assistant never handles credentials.

---

## 5. Step 3: create the repository and push

```bash
gh repo create growercare --private --source . --remote origin --push     # first created private
```

**Finding: GitHub Free can't protect private repositories.** Branch protection and rulesets return
`HTTP 403 "Upgrade to GitHub Pro or make this repository public"`. Check with:
```bash
gh api repos/<owner>/growercare/rulesets        # 403 on Free + private
```
Decision: make the repository **public** and lock `main` with a ruleset (SPEC-PLAT-02 D6).

---

## 6. Step 4: scrub history, then go public (one-time)

Making a repository public exposes every commit's author email. The history was rewritten **before** publishing:

```bash
export FILTER_BRANCH_SQUELCH_WARNING=1
git filter-branch -f \
  --env-filter 'NEW="<id>+<login>@users.noreply.github.com"
    [ "$GIT_AUTHOR_EMAIL"    = "<personal email>" ] && export GIT_AUTHOR_EMAIL="$NEW"    GIT_AUTHOR_NAME="<login>"
    [ "$GIT_COMMITTER_EMAIL" = "<personal email>" ] && export GIT_COMMITTER_EMAIL="$NEW" GIT_COMMITTER_NAME="<login>"' \
  --tree-filter '[ -f CLAUDE.md ] && sed -i "s#<personal path>#%LOCALAPPDATA%/...#" CLAUDE.md || true' \
  -- main <other branches>
git log --all --format='%ae%n%ce' | sort -u        # must show only the no-reply address
git log -p --all | grep -c "<personal path>"       # must be 0
rm -rf .git/refs/original && git reflog expire --expire=now --all && git gc --prune=now
```
- **Check executable bits after rewriting on Windows:** `git ls-tree -r HEAD -- scripts .githooks | awk '{print $1,$4}'` (expect `100755`).
- **GitHub keeps unreferenced old commits** reachable by SHA, so the repository was **deleted and recreated** from the clean history:
  ```bash
  gh auth refresh -h github.com -s delete_repo
  gh repo delete <owner>/growercare --yes
  gh repo create <owner>/growercare --public --description "..."
  git remote set-url origin https://github.com/<owner>/growercare.git
  GC_ALLOW_MAIN_PUSH=1 git push -u origin main && git push -u origin <branches>
  ```

---

## 7. Step 5: workflows (`.github/workflows/`)

### 7.1 Pin every third-party action to a commit SHA
A tag can be moved to malicious code; a commit SHA cannot. Resolve a release to its commit:
```bash
tag=$(gh api repos/actions/checkout/releases/latest --jq .tag_name)
gh api repos/actions/checkout/git/ref/tags/$tag --jq '.object.type + " " + .object.sha'
# if type is "tag" (annotated), dereference:  gh api repos/actions/checkout/git/tags/<sha> --jq .object.sha
```
| Action | Version | Commit |
|---|---|---|
| actions/checkout | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| actions/setup-java | v6.3.0 | `d1374f8ac74474200d16ef8ecee22c7ae7a51880` |
| actions/upload-artifact | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| github/codeql-action | v4.39.3 | `33d7b85da1a04171e211befe2c739303b9a4c066` |
| actions/dependency-review-action | v5.0.0 | `a1d282b36b6f3519aa1f3fc636f609c47dddb294` |
| anthropics/claude-code-action | v1.0.276 | `f3b070c88125d33dbae5653df5f143101d4ea9dc` |

Write uses as `uses: owner/action@<sha> # vX.Y.Z`. Dependabot (§9) keeps them up to date.

### 7.2 `backend-ci.yml`
- **Triggers:** push to `main`, every PR, manual dispatch. `permissions: contents: read` by default.
- **Job `changes`:** detects whether `gc-backend/**` or the workflow changed. It runs on every PR so the required check **always reports**; heavy work only runs when needed.
- **Job `verify`** (the required check), in order:
  1. checkout (`fetch-depth: 0`) and setup-java 21 with the Maven cache;
  2. `mvn -B -ntp verify`: Testcontainers uses the runner's Docker;
  3. `git diff --exit-code -- contracts/`: the API contracts regenerated by the tests must equal the committed ones (drift gate);
  4. **coverage gate:** `python3 scripts/coverage_gate.py gc-backend/coverage/target/site/jacoco-aggregate/jacoco.xml --min-line 80`;
  5. a sticky PR comment with the coverage table (edits the previous comment instead of adding new ones);
  6. SonarQube Cloud: skipped with a notice unless the `SONAR_TOKEN` secret exists;
  7. test reports uploaded as an artifact (14 days).

### 7.3 `pr-policy.yml`
- **Self-tests run first:** `scripts/pr-policy-selftest.sh` (12 cases), `review_gate.py --selftest` (8) and `coverage_gate.py --selftest` (3).
- **Then `scripts/pr-policy.sh`** checks the PR. PR text is passed **only through environment variables** (never `${{ }}` inside `run:`), which prevents script injection.
- **Rules checked:**
  - title `type(scope)?: subject`;
  - a body line `Spec: docs/specs/...` (or `Spec: n/a – reason`);
  - a production-code change needs a test change (or `Tests: n/a – reason`);
  - branch `type/name`;
  - a migration change warns if the schema doc isn't updated.

### 7.4 `codeql.yml`
- CodeQL (`java-kotlin`, `actions`; `build-mode: none`; `security-and-quality`) on push, PR and weekly.
- Dependency review on PRs (fails on high severity).
- Guarded with `if: !github.event.repository.private`, because code scanning on private repos needs GitHub Advanced Security.

### 7.5 `claude-review.yml`: see §10.

---

## 8. Step 6: repository settings and ruleset as code

Everything is applied by **`scripts/apply-repo-settings.sh`**, which is idempotent; re-run it after editing the ruleset:
```bash
export GH="C:/Program Files/GitHub CLI/gh.exe"
bash scripts/apply-repo-settings.sh <owner>/growercare
```
| Setting | Value |
|---|---|
| Merge methods | **squash only**; commit title = PR title, message = PR body |
| Branches | delete head branch after merge; allow update branch; allow auto-merge |
| Wiki / projects | off |
| `GITHUB_TOKEN` default | **read-only**; cannot approve PRs |
| Fork PR workflows | require approval for **all external contributors** |
| Security (public) | secret scanning + **push protection**; private vulnerability reporting; Dependabot alerts + security updates |
| Labels | `dependencies, backend, ci, local-dev, feature, needs-spec, bug, review:critical/high/medium/low/clean/incomplete` |
| Ruleset `main-protection` | from `.github/rulesets/main.json` |

**Ruleset contents** (`.github/rulesets/main.json`):
- **Target:** `~DEFAULT_BRANCH`. **`bypass_actors: []`**.
- **Rules:** `deletion`, `non_fast_forward`, `required_linear_history`.
- **`pull_request`:**
  - approvals 0 (single maintainer; GitHub forbids self-approval);
  - dismiss stale reviews;
  - **review threads must be resolved**;
  - merge method squash.
- **`required_status_checks`** (strict, i.e. the branch must be up to date): `verify`, `policy`, `claude-review`.

**Verify through the API, not the script output:**
```bash
gh api repos/<owner>/growercare --jq '{visibility, allow_squash_merge, allow_merge_commit, allow_rebase_merge, delete_branch_on_merge}'
gh api repos/<owner>/growercare/actions/permissions/workflow
gh api repos/<owner>/growercare/actions/permissions/fork-pr-contributor-approval
gh api repos/<owner>/growercare/rulesets --jq '.[] | {name, enforcement}'
```

**Live test of the lock.** A direct push by the owner must be rejected:
```bash
git switch -c test/lock-check main && git commit --allow-empty -m "test(repo): direct push must be rejected"
GC_ALLOW_MAIN_PUSH=1 git push origin HEAD:main
#  remote: error: GH013: Repository rule violations found ... Changes must be made through a pull request.
git switch - && git branch -D test/lock-check
```

---

## 9. Step 7: supporting governance files

| File | Purpose |
|---|---|
| `.github/CODEOWNERS` | Review requests for specs, ADRs, security code, migrations, contracts, `.github`, `scripts` |
| `.github/pull_request_template.md` | What/why, **`Spec:` line**, verification checklist (tests, smoke, contracts, migrations, security, ADR, skills) |
| `.github/ISSUE_TEMPLATE/feature.yml`, `bug.yml`, `config.yml` | Spec-first feature form, bug form tied to spec rules, blank issues off, security reports routed privately |
| `.github/dependabot.yml` | Weekly grouped updates: Maven (`/gc-backend`), GitHub Actions, docker-compose images; commit prefixes `build`/`ci` |
| `CONTRIBUTING.md` | The workflow, required checks, what to do when `claude-review` blocks |
| `SECURITY.md` | Private reporting, scope, dev-only credentials |
| `README.md` | Map of the repository, quick start, CI badge |
| `.githooks/commit-msg`, `.githooks/pre-push` + `scripts/install-hooks.sh` | Local Conventional-Commit check; no pushes to `main` (`GC_ALLOW_MAIN_PUSH=1` only for documented bootstrap) |

Install the hooks in every clone: `bash scripts/install-hooks.sh`.

---

## 10. Step 8: coverage gate and Claude Code review

### 10.1 80% coverage (merged JaCoCo)
- **Instrumentation:** the parent POM binds `prepare-agent` (unit → `jacoco.exec`) and `prepare-agent-integration` (Failsafe → `jacoco-it.exec`).
- **Aggregation:** the module **`gc-backend/coverage`** runs `report-aggregate` over **both** data files of **all** production modules. Library code exercised by service integration tests counts; `*Application` classes are excluded.
- **Gate:** `scripts/coverage_gate.py` fails when overall line coverage is below 80% and prints a per-module table.
- **Check locally:**
  ```bash
  cd gc-backend && mvn -B verify && cd ..
  python scripts/coverage_gate.py gc-backend/coverage/target/site/jacoco-aggregate/jacoco.xml
  ```
- **Baseline (2026-11-15):** line **91.0–91.2%**, branch ~73%. Lowest module: `gc-shard-routing` (~79–80%).

### 10.2 Claude token (subscription account ≠ GitHub account is fine)
The token is only a string stored as an encrypted repository secret. GitHub never checks which Claude account issued it.
**Run both commands in a separate terminal**, never through the assistant, so the token doesn't enter any chat log:
```powershell
claude setup-token          # browser login with the Claude SUBSCRIPTION email → prints sk-ant-oat01-...
& "C:\Program Files\GitHub CLI\gh.exe" secret set CLAUDE_CODE_OAUTH_TOKEN -R <owner>/growercare   # paste when prompted
cls
```
- **Check:** `gh secret list -R <owner>/growercare` shows the name only.
- **Alternative:** an `ANTHROPIC_API_KEY` secret from console.anthropic.com, with the workflow input switched to `anthropic_api_key`.
- **The Claude GitHub App (`/install-github-app`) is not required:** the workflow passes `github_token: ${{ github.token }}` to post comments.

### 10.3 How `claude-review.yml` works
1. **Fork PRs fail immediately** with "maintainer review required" (forks don't get secrets; `pull_request_target` is never used).
2. **`anthropics/claude-code-action`** (SHA-pinned) runs with:
   - a prompt that encodes the project rules (`CLAUDE.md`, the security checklist, the PR's spec) and the **severity rubric**;
   - inline comments through `mcp__github_inline_comment__create_inline_comment`;
   - `--json-schema`, which returns `{findings:[{severity,file,line,title,detail,rule}], summary}` as `structured_output`.
3. **Deterministic gate** `scripts/review_gate.py`:
   - parses the output strictly; empty or malformed output → **fails closed** ("review incomplete");
   - posts a sticky severity table and sets `review:*` labels;
   - **exits 1 on any Critical/High.**

| Severity | Meaning | Effect |
|---|---|---|
| Critical | Exploitable security flaw or data loss (auth bypass, cross-tenant exposure, injection, committed secret, destructive migration) | Blocks |
| High | Wrong production behaviour or spec violation (403/404 semantics, missing transaction/outbox atomicity, races, 500 paths, PII in logs) | Blocks |
| Medium | Robustness/maintainability (missing parameterized spec rows, convention breaks, N+1) | Flag |
| Low | Style, naming, docs | Flag |

### 10.4 Editing the review workflow, and bot PRs
**Editing `claude-review.yml`.** Older versions of the Claude action refused to run when the PR's copy of its workflow differed from `main`'s (*"Workflow validation failed…"*), so such a PR could not pass the required check. With the pinned **v1.0.276** this no longer happens: on 2026-11-16 the PRs adding `allowed_bots` (growercare#22 and the four code repositories) each received a full review and passed. Only if a future version brings the guard back (the log shows "Workflow validation failed"):
1. remove `claude-review` from `required_status_checks` (Settings → Rules, or the API);
2. review that PR yourself and merge it;
3. re-run `scripts/apply-repo-settings.sh` straight away.

**Dependabot PRs.** The action refuses bot actors (*"Workflow initiated by non-human actor: dependabot (type: Bot)"*) unless they are listed, so the gate failed closed. The workflow sets `allowed_bots: dependabot` (SPEC-PLAT-03 R44), with no wildcard. Dependabot runs only receive **Dependabot** secrets, so each repository also needs `gh secret set CLAUDE_CODE_OAUTH_TOKEN --app dependabot`. PLAT-R23 exempts Dependabot-authored PRs from the `Spec:` line. After changing any of this, comment `@dependabot recreate` on open Dependabot PRs.

### 10.5 False positives
- Reply on the inline comment with the evidence.
- Push a clarifying commit (a test row or comment); the review re-runs on the new diff.
- Never weaken the gate.

### 10.6 Making `claude-review` required
After the workflow is on `main` and the secret exists: `bash scripts/apply-repo-settings.sh` (the ruleset JSON already lists `claude-review`).

### 10.7 SonarQube Cloud (deferred, ready to enable)
1. Sign in to sonarcloud.io with GitHub and create the organization `manjunathyadavs1998` (free plan).
2. Import `growercare` and choose **GitHub Actions** analysis, which disables Automatic Analysis.
3. Create a token and run `gh secret set SONAR_TOKEN -R <owner>/growercare`.
4. If the organization or project key differs, update `sonar.organization` / `sonar.projectKey` in `gc-backend/pom.xml`.

The quality gate ("Sonar way": ≥ 80% coverage on new code, no new bugs/vulnerabilities) then fails `verify` through `sonar.qualitygate.wait=true`.

---

## 11. Everyday workflow for a change

```bash
git switch main && git pull
git switch -c feat/<short-name>                      # types: feat fix docs refactor test build ci chore perf revert
# 1. spec in docs/specs/<service>/  2. parameterized tests from its examples (red)  3. code (green)
cd gc-backend && mvn -B verify && cd ..              # report real totals
git commit -m "feat(case): <subject>"                # commit-msg hook checks the format
git push -u origin HEAD                              # pre-push hook blocks main only
gh pr create --title "feat(case): <subject>" --body "...Spec: docs/specs/case/NN-feature.md..."
gh pr checks <n> --watch                             # verify, policy, claude-review must be green
# merge in the UI with "Squash and merge" (or: gh pr merge <n> --squash --delete-branch)
```

---

## 12. Troubleshooting and lessons learned

| Symptom | Cause | Fix |
|---|---|---|
| `403 Upgrade to GitHub Pro or make this repository public` | Rulesets and branch protection unavailable on Free + private | Make it public (done) or upgrade |
| Settings script treated an error body as a ruleset id | `gh api … --jq` printed the 403 JSON | Accept only numeric ids (`[[ $id =~ ^[0-9]+$ ]]`) |
| Labels created as `review` instead of `review:critical` | `name:color` split on the first colon | Split on the last colon: `${label%:*}` / `${label##*:}` |
| Scripts not executable on Linux runners | Windows doesn't record the exec bit | `git update-index --chmod=+x <files>`; verify with `git ls-files -s` |
| CRLF warnings | Files edited on Windows | `.gitattributes` with `eol=lf`; Git normalises on commit |
| `claude-review`: "review incomplete: empty structured output" | (a) secret missing (for Dependabot PRs: the *Dependabot* secret), (b) bot actor not in `allowed_bots`, or (c) workflow validation in older action versions | (a) set the secret; (b), (c) see §10.4. The step log names the cause |
| `gh pr merge` blocked for the assistant | Assistant permission policy | The maintainer merges in the UI |
| `UnicodeEncodeError` from gate scripts locally | Windows console uses cp1252 | Scripts call `sys.stdout.reconfigure(encoding="utf-8")` |
| Coverage per module looked like 9–40% | Unit and IT reports were separate; libraries weren't credited for service ITs | The `coverage` aggregate module merges everything |
| `-rf :module` build used old library code | Resumed Maven builds read stale jars from `~/.m2` | Re-run the full `mvn -B verify` |
| Docker containers hang / VM freezes during tests | WSL2 VM stalls under memory pressure | Light test DB (11 shards), parallelism 2; reset: quit Docker, `wsl --shutdown`, restart |
| Nested `@TestConfiguration` ignored → 401 in tests | Spring 7 doesn't detect it for `@Nested` classes | `@Import(MyIT.Stubs.class)` on the outer test class |

---

## 13. Command reference

```bash
# GitHub CLI (Windows path)
GH="C:/Program Files/GitHub CLI/gh.exe"
"$GH" run list -R <owner>/growercare --limit 5                  # recent workflow runs
"$GH" run view <id> --log | less                                 # full logs
"$GH" run rerun <id>                                             # re-run (e.g. after adding a secret)
"$GH" pr checks <n> -R <owner>/growercare --watch
"$GH" secret list -R <owner>/growercare                          # names only
"$GH" api repos/<owner>/growercare/rulesets --jq '.[]|{name,enforcement}'
"$GH" api "repos/<owner>/growercare/code-scanning/alerts?state=open"

# Local gates (same as CI)
bash scripts/pr-policy-selftest.sh
python scripts/review_gate.py --selftest
python scripts/coverage_gate.py --selftest
python scripts/coverage_gate.py gc-backend/coverage/target/site/jacoco-aggregate/jacoco.xml

# Re-apply repository settings / ruleset
bash scripts/apply-repo-settings.sh <owner>/growercare

# Rebuild this PDF
pip install markdown-it-py && python docs/ci-setup/build_pdf.py
```

---

## 14. Status and open items (2026-11-15)
| Item | Status |
|---|---|
| Repository public, history scrubbed, `main` locked (no bypass) | Done, verified (GH013 on direct push) |
| backend-ci, pr-policy, CodeQL | Live, green |
| 80% coverage gate | Live (~91%) |
| Claude review workflow + `CLAUDE_CODE_OAUTH_TOKEN` secret | Secret set; the workflow runs once PR #2 is merged into `main` (§10.4) |
| `claude-review` as a required check | After PR #2 merges: `scripts/apply-repo-settings.sh` |
| SonarQube Cloud | Deferred by decision; ready to enable (§10.7) |
