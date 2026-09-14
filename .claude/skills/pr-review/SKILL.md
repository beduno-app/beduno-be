---
name: pr-review
description: >
  Run a general-purpose code review non-interactively in CI against a PR's
  diff: correctness bugs, multi-tenancy/security issues, and
  reuse/simplification/efficiency cleanups. No plan required — runs on any
  PR. Posts inline comments on flagged lines plus one summary comment; never
  fails the workflow. Use whenever the request mentions CI, GitHub Actions,
  automated PR review, or "review this PR".
argument-hint: (none — discovers the PR and diff automatically from the CI environment)
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Agent
---

# PR Review (CI)

Runs inside GitHub Actions via `claude-code-action` on a pull request. Reviews the PR's diff for real defects and worthwhile cleanups — no plan file, no drift analysis, no test-coverage bookkeeping. That is `10x-impl-review-ci`'s job for plan-driven changes; this skill is the general-purpose reviewer for everything else.

**This skill does not interact and does not edit code.** No questions, no source edits, no commits. It reads the diff, analyzes it, posts inline review comments and one summary comment. That's the whole job. It **never** fails the workflow — this check is advisory only.

## Operating context

Running non-interactively on an ephemeral Linux runner with:

- **Git history** — `origin/<base-ref>` is fetched; the PR's merge-base is resolvable.
- **`gh` CLI** — authenticated via `GH_TOKEN`/`GITHUB_TOKEN`; can post comments.
- **Environment variables** — `PR_NUMBER`, `GITHUB_BASE_REF` (base branch), set by the workflow.
- **Subagents** — spawn `general-purpose` agents for parallel review.

No user is watching. Never call `AskUserQuestion`. If anything is ambiguous, pick the conservative interpretation and note it in the summary.

## Step 0: Skip conditions

The workflow already filters drafts via its `if:` condition, but double-check and bail out quietly rather than erroring:

```bash
IS_DRAFT=$(gh pr view "$PR_NUMBER" --json isDraft -q .isDraft 2>/dev/null || echo "false")
if [ "$IS_DRAFT" = "true" ]; then
  echo "Draft PR, skipping review."
  exit 0
fi
```

## Step 1: Compute the diff

```bash
BASE="${GITHUB_BASE_REF:-main}"
git fetch origin "$BASE" --depth=50 2>/dev/null || true
CHANGED_FILES=$(git diff --name-only "origin/${BASE}...HEAD")
```

Skip the review entirely (post nothing, exit 0) if `CHANGED_FILES` is empty, or if every changed file is generated/vendored (build artifacts, lockfiles with no source alongside them).

Don't pre-read every changed file into your own context — delegate that to the subagents below. Keep your own context holding only the file list and diff stats.

## Step 2: Parallel review

Spawn three `general-purpose` subagents in parallel. Give each the changed-file list (filtered to what's relevant to its dimension), the repo root, and instructions to read `CLAUDE.md` for this project's conventions before reviewing. Each agent reads only the files it needs — never the full diff dumped into the prompt.

### Agent 1 — Correctness

Look for actual bugs: logic errors, off-by-one, null/Optional misuse, incorrect exception handling, wrong `@Transactional` boundaries, race conditions, broken edge cases. A finding must name a concrete failure scenario (inputs/state → wrong output or crash) — no "this could theoretically be an issue" without one.

### Agent 2 — Multi-tenancy & security

This codebase is multi-tenant (shared schema, `agency_id` discriminator — see `CLAUDE.md`'s Multi-Tenancy section for the sanctioned cross-tenant exceptions). For every changed repository method or query, verify it filters by `agencyId` unless it matches one of the documented exceptions. Also check for the usual OWASP-class issues: injection, auth/`@PreAuthorize` gaps, secrets in code, unsafe deserialization. A missing tenant filter is the single most damaging class of bug in this codebase — treat it as CRITICAL.

### Agent 3 — Reuse, simplification, efficiency

Flag unnecessary abstraction, duplicated logic that already exists elsewhere in the module, N+1 queries, needless allocations in hot paths, dead code. Skip stylistic nitpicks Checkstyle already enforces (CI runs `./gradlew build`, which fails on Checkstyle violations — don't re-report those).

Each agent reports findings in this shape (or none, if it found nothing worth flagging):

```
- file: <path>
- line: <line number in the new version, or omit if not line-anchored>
- severity: CRITICAL | WARNING | OBSERVATION
- summary: <one sentence, the claim itself>
- detail: <what's wrong, why>
- fix: <concrete suggested change>
```

## Step 3: Normalize findings

Merge the three agents' output into one list. Sort CRITICAL → WARNING → OBSERVATION. Cap at 10 total — consolidate near-duplicates (e.g. the same missing-`agencyId`-filter pattern in three repositories → one finding listing all three locations, not three separate findings). Drop anything that's really a matter of taste with no concrete failure scenario or benefit.

## Step 4: Post inline comments

Findings with a `file:line` that lands inside the PR's diff become inline comments. Everything else (no line anchor, or the line isn't in a changed hunk) goes to the summary only. Set `N_INLINE_ELIGIBLE` to the count in the first group *before* attempting to post any of them — this is the number this run *should* produce, independent of how posting actually goes, and the cleanup step below needs it.

```bash
line_in_diff() {
  local file="$1" line="$2"
  git diff --unified=0 "origin/${BASE}...HEAD" -- "$file" \
    | awk -v target="$line" '
        /^@@/ {
          match($0, /\+([0-9]+)(,([0-9]+))?/, m);
          start = m[1]; count = (m[3] == "" ? 1 : m[3]);
          if (target >= start && target < start + count) { found = 1; exit }
        }
        END { exit !found }
      '
}
```

For each inline-eligible finding, call `mcp__github_inline_comment__create_inline_comment` once, with `confirmed: true` (this skill's findings are already the triage decision — don't buffer them through the action's classifier):

```
mcp__github_inline_comment__create_inline_comment({
  path: "<finding.file>",
  line: <finding.line>,
  side: "RIGHT",
  confirmed: true,
  body: "<severity icon> **<SEVERITY>** · <dimension> — <summary>\n\n<detail>\n\n**Fix:** <fix>\n\n<!-- pr-review:marker -->"
})
```

Severity icons: ❌ CRITICAL · ⚠️ WARNING · 👁 OBSERVATION. Always pair the icon with the word — never a bare icon.

Track `N_INLINE_POSTED` and `N_INLINE_ELIGIBLE` (the count of findings that *should* have been posted inline this run, before any failures). These are different things: `N_INLINE_POSTED == 0` because there was nothing to post (a clean run) must clean up old comments same as any other successful run; `N_INLINE_POSTED == 0` because posting failed must not.

### Clean up prior run's inline comments

Delete this PR's prior `pr-review` inline comments (identified by the marker) that predate this run — **unless** this run had findings to post and every single one failed, in which case leave the old comments as the only visible feedback:

```bash
NOW_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)   # capture BEFORE the first create_inline_comment call
# ... after posting ...
if [ "$N_INLINE_ELIGIBLE" -eq 0 ] || [ "$N_INLINE_POSTED" -gt 0 ]; then
  gh api --paginate "repos/{owner}/{repo}/pulls/${PR_NUMBER}/comments" \
    --jq ".[] | select(.body | contains(\"<!-- pr-review:marker -->\")) | select(.created_at < \"${NOW_ISO}\") | .id" \
    | while read -r COMMENT_ID; do
        gh api --method DELETE "repos/{owner}/{repo}/pulls/comments/${COMMENT_ID}" 2>/dev/null || true
      done
fi
```

**This condition is load-bearing — a common bug to avoid:** gating cleanup on `N_INLINE_POSTED -gt 0` alone (without the `N_INLINE_ELIGIBLE -eq 0` escape hatch) means a PR that goes from "had findings" to "clean" never gets its stale inline comments removed, since a clean run posts nothing new and the old ones linger forever pointing at lines that may no longer even exist in the diff. Post-new-then-delete-old only protects against the *failure* case — every eligible finding this run failed to post — not the *nothing-to-post* case.

## Step 5: Post the summary comment

```bash
gh pr comment "$PR_NUMBER" --body "$(cat <<EOF
## 🔍 PR Review (CI)

${N_CRITICAL} critical, ${N_WARNINGS} warnings, ${N_OBSERVATIONS} observations
**Inline comments:** ${N_INLINE_POSTED} posted on changed lines · ${N_SUMMARY_ONLY} without a line anchor (below)

$( [ "$N_CRITICAL" = "0" ] && [ "$N_WARNINGS" = "0" ] && [ "$N_OBSERVATIONS" = "0" ] && echo "No findings." )

${SUMMARY_ONLY_FINDINGS_MARKDOWN}

---
This review is advisory — it never blocks the merge. Read [CLAUDE.md](../CLAUDE.md) for this repo's conventions.

<!-- pr-review:marker -->
EOF
)" && SUMMARY_POSTED=1
```

Render each summary-only finding as one compact line:

```
- **CRITICAL** · Multi-tenancy — `WorkerRepository.findRecentlyActive` has no `agencyId` filter; returns rows across tenants.
```

Cap the summary list at 5 with `…and N more inline.` if more exist.

### Clean up prior run's summary comment

Same pattern, on the issue-comments endpoint (PR summary comments are GitHub issue comments under the hood):

```bash
if [ "$SUMMARY_POSTED" = "1" ]; then
  gh api --paginate "repos/{owner}/{repo}/issues/${PR_NUMBER}/comments" \
    --jq ".[] | select(.body | contains(\"<!-- pr-review:marker -->\")) | select(.created_at < \"${NOW_ISO}\") | .id" \
    | while read -r COMMENT_ID; do
        gh api --method DELETE "repos/{owner}/{repo}/issues/comments/${COMMENT_ID}" 2>/dev/null || true
      done
fi
```

## Operational notes

- **Never fail the workflow.** No finding, no combination of findings, exits non-zero. This is an advisory check only — the build/checkstyle/test job in `ci.yml` is what gates merges.
- **Never edit source code or commit anything.** Read, analyze, comment — nothing else.
- **Dedup marker is load-bearing.** Every comment this skill posts ends with `<!-- pr-review:marker -->`; the next run uses it to retire this run's own prior comments so they don't pile up across pushes.
- **Don't echo secrets.** If a finding involves a hardcoded credential or token, redact the value in the comment — write `<REDACTED>`, never the literal string.
- **Skip Checkstyle-shaped nitpicks.** `./gradlew build` already enforces style; repeating those findings just adds noise.
