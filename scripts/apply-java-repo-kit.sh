#!/usr/bin/env bash
# ADR-017 / SPEC-PLAT-06 R81: copies the GrowerCare repo kit (templates/java-repo) into a Java repository, so
# every library and service repository has the same gates (ci, pr-policy, claude-review, codeql, ruleset, hooks).
#
#   scripts/apply-java-repo-kit.sh <target-dir> lib|service
#
# Re-run it to bring a repository back in sync with the kit (it overwrites kit-owned files only;
# .gitignore is merged so repository-specific entries are kept).
set -euo pipefail
cd "$(dirname "$0")/.."
KIT=templates/java-repo
TARGET=${1:?target directory}
KIND=${2:?lib or service}
[[ -d "$TARGET/.git" ]] || { echo "not a git repository: $TARGET" >&2; exit 2; }

case "$KIND" in
  lib)     COVERAGE_XML=coverage/target/site/jacoco-aggregate/jacoco.xml; SKIP=image.yml ;;
  service) COVERAGE_XML=target/site/jacoco-merged/jacoco.xml;            SKIP=release.yml ;;
  *) echo "kind must be lib or service" >&2; exit 2 ;;
esac

# Governance files (kit-owned)
(cd "$KIT" && find . -type f ! -name "$SKIP") | while read -r file; do
  mkdir -p "$TARGET/$(dirname "$file")"
  if [[ "$file" == ./.gitignore && -f "$TARGET/.gitignore" ]]; then
    # Merge, never overwrite: the repository's own ignores (e.g. services/*.env) must survive a re-apply.
    while IFS= read -r line || [[ -n "$line" ]]; do
      grep -qxF -- "$line" "$TARGET/.gitignore" || printf '%s\n' "$line" >> "$TARGET/.gitignore"
    done < "$KIT/$file"
  else
    cp "$KIT/$file" "$TARGET/$file"
  fi
done
sed -i "s#__COVERAGE_XML__#${COVERAGE_XML}#" "$TARGET/.github/workflows/ci.yml"
chmod +x "$TARGET"/scripts/*.sh "$TARGET"/.githooks/* 2>/dev/null || true

# Review context for the Claude review (CLAUDE.md is repo-specific and written by hand)
mkdir -p "$TARGET/.claude/skills" "$TARGET/.claude/agents"
for skill in spec-driven-development java-backend-standards java-persistence-sql java-security-rbac \
             java-exception-handling event-contract api-contract test-strategy sharded-migration; do
  rm -rf "$TARGET/.claude/skills/$skill"
  cp -r ".claude/skills/$skill" "$TARGET/.claude/skills/$skill"
done
cp .claude/agents/security-reviewer.md "$TARGET/.claude/agents/"

echo "kit applied to $TARGET ($KIND); coverage report: $COVERAGE_XML"
