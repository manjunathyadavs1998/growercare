#!/usr/bin/env bash
# SPEC-PLAT-02 D1/D2: apply repository settings and the main-branch ruleset (as code) with the GitHub CLI.
# Idempotent: safe to re-run after editing .github/rulesets/main.json.
#   scripts/apply-repo-settings.sh [owner/repo]
set -euo pipefail
cd "$(dirname "$0")/.."
GH=${GH:-gh}
command -v "$GH" >/dev/null 2>&1 || GH="/c/Program Files/GitHub CLI/gh.exe"
REPO=${1:-$("$GH" repo view --json nameWithOwner --jq .nameWithOwner)}

echo "== $REPO: merge settings (D2: squash only, delete merged branches, auto-merge)"
"$GH" api -X PATCH "repos/$REPO" --silent \
  -F allow_squash_merge=true -F allow_merge_commit=false -F allow_rebase_merge=false \
  -f squash_merge_commit_title=PR_TITLE -f squash_merge_commit_message=PR_BODY \
  -F delete_branch_on_merge=true -F allow_auto_merge=true -F allow_update_branch=true \
  -F has_wiki=false -F has_projects=false -F web_commit_signoff_required=false
echo "   done"

echo "== Dependabot alerts + security updates (R28)"
"$GH" api -X PUT "repos/$REPO/vulnerability-alerts" --silent && echo "   alerts on"
"$GH" api -X PUT "repos/$REPO/automated-security-fixes" --silent && echo "   security updates on"

echo "== Labels used by Dependabot and templates"
for label in "dependencies:0366d6" "backend:5319e7" "ci:fbca04" "local-dev:c5def5" "feature:a2eeef" "needs-spec:d93f0b" "bug:d73a4a"; do
  "$GH" label create "${label%%:*}" --color "${label##*:}" --repo "$REPO" --force >/dev/null && echo "   ${label%%:*}"
done

echo "== Ruleset 'main-protection' (R20/R21)"
existing=$("$GH" api "repos/$REPO/rulesets" --jq '.[] | select(.name=="main-protection") | .id' 2>/dev/null || true)
[[ "$existing" =~ ^[0-9]+$ ]] || existing=""      # a 403 body is not an id
if out=$( { if [[ -n "$existing" ]]; then
              "$GH" api -X PUT "repos/$REPO/rulesets/$existing" --input .github/rulesets/main.json
            else
              "$GH" api -X POST "repos/$REPO/rulesets" --input .github/rulesets/main.json
            fi; } 2>&1 ); then
  echo "   ruleset active"
elif grep -q "Upgrade to GitHub Pro" <<<"$out"; then
  echo "   NOT APPLIED: this plan does not allow rulesets on private repositories."
  echo "   Upgrade to GitHub Pro or make the repository public, then re-run this script."
  echo "   Until then: CI checks + local hooks (scripts/install-hooks.sh) enforce the rules client-side."
else
  echo "$out" >&2
  exit 1
fi
