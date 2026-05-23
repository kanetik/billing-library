# Automated PBL update playbook

This playbook is invoked by a **Claude.ai Routine** on a daily schedule. The Routine fires the prompt in section 12 below; that prompt reads this doc and acts on it.

**Audience:** the agent firing on the Routine, plus the human maintainer who reviews the PRs (and occasionally the issues) it opens.

**Purpose:** detect new stable PBL releases, categorize them as safe or risky, fix anything the bump breaks in the wrapper, and open a high-quality PR against the correct branch for human review. The agent never auto-merges, never tags, never pushes to `main` directly.

---

## 1. Purpose & guardrails

### What the agent does

- Detects new PBL stable versions
- Reads both the release notes and the "Migrate to Billing Library X" guide — guide is required reading when a major boundary is in scope, advisory (but worth skimming) otherwise
- Categorizes each release as **safe** (bug fix / internal-only) or **risky** (API change / behavior change / minSdk bump)
- Fixes any code or test failures the bump introduces, on the appropriate branch
- Opens or updates a PR against `main` (safe) or `next` (risky), with categorization rationale and a clear summary of what changed (and refreshes an existing open bump PR in place rather than opening a duplicate — see section 3)
- Opens a separate `[feat proposal]` GitHub issue (labeled `question`) for each net-new PBL feature, @-mentioning the maintainer
- Notifies the maintainer via IFTTT (SMS to Android) when a new PR is opened, or when an existing PR's target version is bumped (a stale-target refresh per section 3 — audit fixes and rebase-only refreshes do **not** re-ping), plus one ping per feature-proposal issue and one per intervention issue

### What the agent never does

- Push directly to `main`
- Merge any PR
- Tag any release
- Bump any dependency other than `playBillingKtx`
- **Open a PR (draft or otherwise) with a failing build or failing tests.** This is absolute. If the bump breaks something, the agent fixes it before opening the PR. If it can't fix in three attempts, it opens an *issue* instead, with full diagnostic detail.
- **Auto-include net-new PBL features in the bump PR.** New PBL APIs the wrapper does not already expose go to a feature-proposal issue (section 4). The only exception is a trivial overload of a method the wrapper already wraps, where the new signature follows mechanically from the existing wrapping — and that exception is **Path B only**, because any new function in `com.kanetik.billing.*` is a public-API change.

### Working-assumption when something fails

When a test fails after the bump, **the default assumption is that the test is correct and the wrapper code (or the PBL behavior the wrapper relied on) is what needs to change**. The test failure is the bump's signal that something material shifted. Don't reach for the test as the cause unless the wrapper-side investigation rules everything else out.

It's not impossible for the test to be at fault — but the order of investigation is: PBL behavior change → wrapper internal code → test, in that order. If the test really is wrong, fixing it is fine, but the PR body should explicitly call that out so the maintainer knows.

### Public API changes

- **On `main` (Path A):** never. If the bump turns out to require any change to types or signatures in `com.kanetik.billing.*`, the categorization flips from safe to risky and the work moves to `next`.
- **On `next` (Path B):** allowed and expected. Risky bumps may require new types, removed types, or signature changes in `com.kanetik.billing.*` to absorb upstream PBL changes cleanly. The PR body must enumerate every public-API change with a "**BREAKING:**" prefix so reviewers can audit them in one place.

### Draft PRs are for *ambiguity*, not for failures

A draft PR is the right tool when the agent's categorization confidence is below ~80%, when a migration choice has multiple reasonable paths, or when the agent is uncertain about a public-API decision on `next`. In those cases the agent opens a draft and posts its specific questions in the PR description (and follows up in PR comments if more come up while writing).

A draft PR is **not** acceptable as a way to ship work with failing tests or a broken build. If something's failing, the agent fixes it (section 7) or opens an issue.

---

## 2. Detection

### Trigger

The Claude.ai Routine fires the prompt in section 12 daily.

### Steps

1. Fetch PBL's Maven metadata:
   ```
   curl -fsSL https://dl.google.com/android/maven2/com/android/billingclient/billing-ktx/maven-metadata.xml
   ```
2. Extract the latest stable version (filter out `*-alpha*`, `*-beta*`, `*-rc*`, `*-dev*`):
   ```bash
   curl -fsSL <url> | grep -oE '<version>[^<]+</version>' | sed -E 's|</?version>||g' \
       | grep -vE '(alpha|beta|rc|dev|RC|preview|SNAPSHOT|M[0-9]+)' | tail -1
   ```
3. Fetch the wrapper's currently pinned version:
   ```
   curl -fsSL https://raw.githubusercontent.com/kanetik/billing-library/main/gradle/libs.versions.toml | grep '^playBillingKtx'
   ```
4. Compare.

### Decision

- **Pinned == latest stable:** silent exit. No PR, no issue, no notification, no log message.
- **Pinned < latest stable:** proceed to categorization. Note the full version delta — if multiple versions came out since the pin (e.g., pinned 8.3.0, latest 8.5.0, intermediate 8.4.0), include all release notes in scope.

---

## 3. Check for existing open PR

Before branching, editing, or running any verification, look for an open PR that already targets this bump. The Routine fires daily — without this step, every day a still-open bump PR remains unmerged would spawn a duplicate.

### Lookup

```bash
gh pr list --state open --limit 200 \
  --json number,title,headRefName,baseRefName,isDraft,updatedAt \
  --jq '[.[] | select(.headRefName | startswith("bump/pbl-"))]'
```

Notes on the command shape:

- `--search "head:bump/pbl-"` is **not** used because GitHub's PR search treats `head:` as an exact branch-name match, not a prefix — filtering client-side with `--jq startswith` is the reliable way to catch any `bump/pbl-<VERSION>` or `bump/pbl-<VERSION>-beta` branch.
- `--limit 200` is well above the worst-case number of open PRs on this repo so the lookup doesn't silently truncate.
- `body` is intentionally omitted from `--json` — it would pull the full PR description for every open PR. Fetch the body only for matched candidates: `gh pr view <NUMBER> --json body,title,baseRefName,headRefName,...`.

A match is any open PR whose head branch starts with `bump/pbl-`. Identify the target version from the branch name (`bump/pbl-<VERSION>` or `bump/pbl-<VERSION>-beta`); fetch the body via `gh pr view` for the matched PR and read its "Version delta" section to confirm.

### Decisions

**No matching open PR** → proceed to section 4 (categorization).

**An open PR targets exactly the latest stable version** (e.g., latest is 8.5.0 and the PR is for 8.5.0):

1. Read the PR end-to-end — title, body, diff, file changes, comments, CI status.
2. Audit it against this playbook:
   - Categorization correct (safe vs risky, Path A vs Path B, right base branch)?
   - Every change in release notes enumerated in the PR body?
   - For Path B, every public-API change labeled `**BREAKING:**`?
   - **Migration guide URL** present in the PR body (if a major boundary is crossed)?
   - **Feature-proposal issues** linked under "Feature proposals deferred to follow-up issues" (if any net-new PBL features exist in the version range)?
   - CHANGELOG entry present and in the templated format from section 5 or 6?
   - Latest commit passes `:billing:test :sample:assembleDebug :billing:lint`?
   - Any maintainer review comments unaddressed?
3. If any audit item is missing or wrong, work on the **existing branch** — never open a duplicate PR. Do the following in this exact order:
   - Pull the branch locally and rebase its base (`main` or `next`) into it so the fixes and the verification run against the current state of the base branch.
   - Apply the audit fixes. If they changed the categorization, release-notes enumeration, or linked feature-proposal issues, also update the PR title and body to match.
   - Re-run verification (`:billing:test :sample:assembleDebug :billing:lint`). Only continue if it passes; if it fails, go to section 7.
   - Push with `git push --force-with-lease` (the rebase rewrote history, so a plain push will be rejected as non-fast-forward; `--force-with-lease` is preferred over `--force` because it refuses to clobber commits the agent hasn't seen).
   - Add a PR comment summarizing what changed and why, so the maintainer can see what shifted since their last look. No IFTTT — the maintainer was already pinged when the PR opened.
   - **No-changes-needed but base has moved:** if the audit shows the PR is already correct but its base branch has moved since the last commit, refresh the branch (rebase + re-run verification + `git push --force-with-lease`) so the PR's CI reflects the current base. Post a brief PR comment noting the rebase. No IFTTT notification — the maintainer was already pinged when the PR opened.
4. Do not overrule the maintainer. If the maintainer has commented endorsing a specific categorization, public-API decision, or migration choice, leave it alone — only fix mechanical gaps (missing CHANGELOG bullet, failing verification, missing release-notes enumeration).
5. If everything is already correct: exit silently. Do **not** re-notify; the maintainer was already pinged when the PR opened.

**An open PR targets a stale version** (e.g., PR is for 8.5.0 but latest stable is now 8.5.1, or pinned was 8.3.0 and the old PR jumped to 8.4.0 while latest is now 8.5.0):

1. Decide whether to update in place or re-route:
   - Old PR was Path A and the delta from `<OLD_TARGET>` → `<NEW_TARGET>` adds only safe items → **update in place**.
   - Old PR was Path A but the additional versions in scope add risky items → categorization flips. Open a fresh PR on `next` per section 6. On the old Path A PR, post a comment (only if a `Superseded by #` comment isn't already there — keep this idempotent across daily Routine runs): `Superseded by #<NEW_PR>. The newer versions in scope require public-API changes; work moved to next. This PR can be closed.` Leave the old PR open for the maintainer to close. **This is the single explicit exception to §10's "no duplicate bump PRs" rule** — see §10.
   - Old PR was Path B → keep it on `next`; update in place.
2. Updating in place:
   - Pull the branch locally and rebase its base (`main` or `next`) into it so it's current.
   - Bump `playBillingKtx` to the new latest.
   - Extend the CHANGELOG entry's release-notes summary to cover the newly-in-scope versions; widen the version-delta range in the PR body.
   - Re-run verification (`:billing:test :sample:assembleDebug :billing:lint`). Fix failures per section 7.
   - Update the PR title to reflect the new `<NEW>` version. Update the body's Version delta, Migration guide (if a major boundary is now in scope), Categorization, Feature proposals deferred to follow-up issues (if any new ones were opened for the additional versions), and (for Path B) Risky items / Public API changes sections.
   - Force-push the updated branch with `git push --force-with-lease` (rebase rewrites history; `--force-with-lease` is preferred over `--force` because it refuses to clobber commits the agent hasn't seen).
   - Post a PR comment: `Refreshed: target bumped <OLD_TARGET> → <NEW_TARGET>; added <N> additional release-notes items to scope; verification re-run.`
3. Send the IFTTT notification (section 11) noting the refresh: `Kanetik PBL update PR refreshed <OLD_TARGET> -> <NEW_TARGET>: <PR_URL>`.

**Multiple open PRs match** (the common case is the Path A → Path B supersession above, but otherwise rare): treat the most recently updated one as authoritative. On each other matching PR, post a comment pointing at the authoritative PR (`Superseded by #<NUM>; this PR can be closed.`) — but skip the comment if such a comment from the agent is already present, so daily Routine runs don't pile up duplicate supersede comments. Do not auto-close — let the maintainer.

### What "as good as possible" means here

The agent's job is to hold the PR to this playbook's mechanical bar: right base, templated body, CHANGELOG entry, passing verification, every release-notes item accounted for, every public-API change flagged. It is **not** to second-guess editorial judgments the maintainer has explicitly endorsed.

---

## 4. Categorization rubric

For each version in scope, read **both** of these Google sources:

1. **Release notes** — <https://developer.android.com/google/play/billing/release-notes> — the per-version change list.
2. **Migration guide** — `https://developer.android.com/google/play/billing/migrate-<MAJOR>` (e.g., `migrate-8` for the 8.x line, linked from the left nav of the release-notes page). **Required reading** whenever the version range in scope crosses a major boundary (e.g., pinned 7.x → latest 8.x). For minor/patch-only bumps, skim it anyway — it sometimes flags items the release notes understate or omit.

Cross-reference the two. If the migration guide describes something the release notes didn't (or vice versa), treat that as a categorization signal — escalate to risky if there's any doubt. Then classify each change using the rubric below.

### Safe (route to `main`, wrapper patch bump)

- Pure bug fix
- Performance improvement
- Internal refactor (PBL's own internals, no consumer-visible change)
- Documentation-only change

### Risky (route to `next`, wrapper minor or major beta)

- **Any deprecation** of an API the wrapper uses internally (`grep` the wrapper for the deprecated symbol — if found, route as risky even if it still compiles)
- **Any removal** of an API
- **Behavior change** of an existing API (release notes say "behavior change", "now returns", "changed semantics", etc.)
- **New minSdk requirement** (e.g., PBL 8.1 raised the floor from 21 to 23)
- **New permission** required in the manifest
- **New build-script requirement** (Kotlin/AGP/Gradle minimum version)

Net-new PBL features are handled separately — see the next subsection.

### Net-new PBL features → feature-proposal issue (do not auto-include)

A net-new PBL feature is any new API, method, class, or capability the wrapper does not currently expose. **The agent does not unilaterally decide whether to include a net-new feature in the bump PR, and does not unilaterally decide a feature isn't worth including either** — both are design calls for the maintainer.

Default behavior, for every net-new PBL feature:

1. Open a **separate** GitHub issue (one per feature) titled `[feat proposal] Expose PBL <feature name> (added in <VERSION>)` and label it `question`. Use `--body-file` (or stdin via `--body-file -`) so the multi-line markdown body from step 2 keeps its formatting — quoting a multi-line markdown blob into `--body "..."` is brittle:
   ```bash
   gh issue create \
     --title "[feat proposal] Expose PBL <feature name> (added in <VERSION>)" \
     --label question \
     --body-file - <<'EOF'
   <body from step 2>
   EOF
   ```
   (Or write the body to a temp file and pass `--body-file /path/to/body.md`.) If the `question` label doesn't exist on the repo, create it first (`gh label create question --color cc317c --description "Needs maintainer decision"`).
2. Body template:
   ```
   PBL <VERSION> added <feature name>. The wrapper does not currently expose it.

   Release notes: <URL, anchored to this feature if possible>
   Migration guide section: <URL anchored to this feature, if applicable>

   What PBL added:
   <one-paragraph description from release notes / migration guide>

   How a consumer would use it via raw PBL today:
   <short code sketch>

   Plausible wrapper shape:
   <code sketch if the shape is obvious; otherwise "TBD — needs design decision">

   @kanetik — does this belong in the wrapper? If so, how should it be shaped?

   This issue is intentionally separate from the bump PR so the bump stays focused on parity with the new PBL version. The agent will not include this feature in the bump PR.
   ```
3. Link every feature-proposal issue from the bump PR body under a "Feature proposals deferred to follow-up issues" section (templates in sections 5 and 6).
4. Do **not** include the feature in the bump PR's diff.
5. Fire one IFTTT notification per feature-proposal issue (section 11).

**Zero-ambiguity exception (rare — default is still to open an issue):** if the new PBL API is unambiguously the wrapper's job to expose with no design choice required — most commonly a new overload of a method the wrapper already wraps, where the new overload's wrapped signature follows mechanically from the existing one — the agent **may** include it in the bump PR. When it does:

- **Path B / `next` only.** Adding any new function to `com.kanetik.billing.*` — even a trivial mechanical overload — is a public-API change, and per §1's guardrails public-API changes are forbidden on Path A / `main`. If you're on Path A and an overload would qualify for this exception, treat it as a re-categorization mid-flow (see "Re-categorization mid-flow" later in this section): abandon the Path A bump and re-open the work on `next` per section 6. The branch-switching mechanics in section 7 apply even though the trigger here is the exception applying, not a test failure. Alternatively, drop the overload from the bump PR and open it as a feature-proposal issue instead, exactly like a non-trivial feature. On Path A the exception itself is **not available** in the current PR.
- List every such addition under a "Net-new PBL surface included for parity" section in the PR body (Path B template only — section 6).
- For each, give a one-line rationale for why it was a mechanical add (e.g., "PBL added a `BillingClient.queryPurchasesAsync(QueryPurchasesParams, Continuation)` Kotlin-coroutine overload of the existing wrapped `queryPurchasesAsync(QueryPurchasesParams, PurchasesResponseListener)`; the wrapper's coroutine wrapper now delegates to the new overload — no design choice").
- If you're under ~95% confident the add is mechanical: don't include it. Open the issue instead.

Anything beyond a trivial overload — new classes, new top-level methods, new behavior surfaces — always goes to an issue, never into the bump PR.

The cost of a deferred feature is "maintainer sees one extra issue in their inbox." The cost of auto-including the wrong API shape is a design mistake the wrapper has to support forever.

### Borderline → escalate (open as draft, flag in PR body with explicit questions)

- New deprecated API where the wrapper might want to migrate even though the old still works
- Anything labeled "important" or "behavior change" in release notes that's hard to categorize
- A change where the agent's confidence is < 80%

When unsure, route as risky. Cost of a false-risky is "human reviews a PR that could have been simpler"; cost of a false-safe is a regression on `main`.

### Multiple versions in scope

If multiple PBL versions accumulated since the last pin, take the **most-conservative** classification across all of them. If any version in the range is risky, the entire bump is risky.

### Re-categorization mid-flow

If the agent starts on Path A (safe) and discovers during the fix loop that the necessary fix requires public-API changes, the categorization **flips to risky**. Abandon the `main` branch work, switch to `next`, and follow Path B. Section 7 covers this transition explicitly.

---

## 5. Path A — all-safe bump (target `main`)

Branch from `main` as `bump/pbl-<NEW_VERSION>` (e.g., `bump/pbl-8.3.1`).

### Edits

1. `gradle/libs.versions.toml`: update `playBillingKtx = "<NEW_VERSION>"`.
2. `CHANGELOG.md`: add a new dated section between `## [Unreleased]` and the most recent released section. Use today's date.
   ```markdown
   ## [<NEXT_PATCH_VERSION>] - <YYYY-MM-DD>

   ### Changed

   - Bumped Play Billing Library `<OLD>` → `<NEW>` ([release notes](<URL>)).
     <one-line summary of meaningful changes from release notes, or "Bug fixes only.">
   ```
   `<NEXT_PATCH_VERSION>` = current released version with patch incremented (e.g., `0.1.0` → `0.1.1`).

### Verification (must pass before opening the PR)

```bash
./gradlew :billing:test :sample:assembleDebug :billing:lint
```

If anything fails: do **not** open a PR. Go to section 7 (Test failure handling).

### Open the PR

- **Title:** `build(deps): bump play-billing-ktx <OLD> → <NEW>`
- **Base branch:** `main`
- **Body:**
  ```
  Automated bump by daily PBL update Routine.

  ## Version delta
  Pinned: <OLD>
  Latest stable: <NEW>
  Release notes: <URL>
  Migration guide: <URL if any major boundary in scope, else "N/A — no major boundary crossed">

  ## Categorization
  Safe — patch bump on main.

  Reasoning:
  - <bullet for each change in release notes, with the safe-rationale>

  (Path A never introduces net-new public surface in the wrapper — see
  section 4's zero-ambiguity exception, which is Path B only. If a
  trivial overload looks like it ought to be included, the categorization
  flips to risky and the work moves to `next`.)

  ## Feature proposals deferred to follow-up issues
  <if none>: None — no net-new PBL features in this version range.
  <if any>:
  - #<ISSUE_NUM>: <feature name> — see issue for design discussion.
  - <repeat for each>

  ## Verification
  - :billing:test — 58 tests passed
  - :sample:assembleDebug — succeeded
  - :billing:lint — clean

  ## CHANGELOG
  Stamped as `[<NEXT_PATCH_VERSION>] - <YYYY-MM-DD>`. Adjust the date at merge time if it slips.

  ## What the maintainer should do
  1. Skim the release notes link and confirm the categorization.
  2. Triage any linked feature-proposal issues (separate from this PR).
  3. Merge this PR if happy.
  4. Tag `v<NEXT_PATCH_VERSION>` and push to trigger the publish workflow.
  ```

After opening the PR: send the IFTTT notification (section 11).

---

## 6. Path B — risky bump (target `next`)

If the `next` branch doesn't exist, create it from `main` and push it before doing anything else:
```bash
git checkout -b next main
git push origin next
```
Mention this branch creation in the PR body so the maintainer knows it's a first-time event.

Branch from `next` as `bump/pbl-<NEW_VERSION>-beta` (e.g., `bump/pbl-8.5.0-beta`).

### Edits

1. `gradle/libs.versions.toml`: update `playBillingKtx`.
2. **Internal code adjustments as needed.** Unlike Path A, Path B may include public-API changes:
   - If a deprecated API the wrapper uses is still callable, prefer migrating to the new API rather than leaving a TODO. Be explicit in the PR body about why the migration was chosen.
   - If an API was *removed*, the agent migrates the wrapper. If the wrapper's public surface needs to change to absorb the removal, do it — but list every public-API change with a `**BREAKING:**` prefix in the PR body.
   - **Net-new PBL features still go to feature-proposal issues, not into this PR** (see section 4's "Net-new PBL features" subsection). Path B is for absorbing risky upstream changes that affect what the wrapper already exposes, not for opportunistically expanding the wrapper's surface. The zero-ambiguity exception in section 4 still applies for trivial overloads of methods the wrapper already wraps.
3. `CHANGELOG.md` on the `next` branch: add an entry under `## [Unreleased]`:
   ```markdown
   ### Changed

   - **(beta)** Bumped Play Billing Library `<OLD>` → `<NEW>` ([release notes](<URL>)).
     <summary>

   ### Public API changes (BREAKING — bump major if any)

   - <bullet for each public-API change with rationale>

   ### Risky items flagged for follow-up

   - <bullet for each risky item, with link to release-notes section>
   ```
4. **Do not** modify `[Unreleased]` on `main` for this work.

### Verification (must pass before opening the PR)

Same as Path A: `./gradlew :billing:test :sample:assembleDebug :billing:lint`. If anything fails, go to section 7.

### Open the PR

- **Title:** `build(deps): bump play-billing-ktx <OLD> → <NEW> (beta — risky changes flagged)`
- **Base branch:** `next`
- **Body:**
  ```
  Automated bump by daily PBL update Routine. Routed to `next` because the
  release contains changes that warrant maintainer review before merging
  to `main`.

  ## Version delta
  Pinned: <OLD>
  Latest stable: <NEW>
  Release notes: <URL>
  Migration guide: <URL if any major boundary in scope, else "N/A — no major boundary crossed">

  ## Categorization
  Risky — minor or major beta on `next`.

  ### Risky items
  - <each risky change with: what it is, why it's risky, what the wrapper now does>

  ### Public API changes
  <if none>: None — wrapper public API is unchanged.
  <if any>:
  - **BREAKING: <one-line description of change>**
    Rationale: <why this change was needed>
    Migration for consumers: <if any>
  - <repeat for each>

  ### Net-new PBL surface included for parity
  <if none>: None — the bump introduces no new public surface in the wrapper.
  <if any (zero-ambiguity exception per section 4)>:
  - <one-line description of addition>
    Rationale: <why this was a mechanical add>

  ### Feature proposals deferred to follow-up issues
  <if none>: None — no net-new PBL features in this version range.
  <if any>:
  - #<ISSUE_NUM>: <feature name> — see issue for design discussion.
  - <repeat for each>

  ### Safe items (along for the ride)
  - <each safe change>

  ## Suggested wrapper version
  <NEXT_VERSION>-beta1
  Rationale: <minor if API additive, major if any BREAKING items above>

  ## Branch creation note (if applicable)
  This PR is the first time the `next` branch was used. Created from `main` at
  commit <sha> on <date>.

  ## Verification
  - :billing:test — 58 tests passed
  - :sample:assembleDebug — succeeded
  - :billing:lint — clean

  ## What the maintainer should do
  1. Read the risky items list and confirm the migration strategy for each.
  2. Audit the public API changes (each `**BREAKING:**` line).
  3. Triage any linked feature-proposal issues (separate from this PR).
  4. Merge to `next` when satisfied with the bump itself.
  5. Tag a `vX.Y.Z-beta1` release from `next` to publish the beta.
  6. After dog-fooding the beta, fast-forward `main` to `next` and tag `vX.Y.Z` GA.
  ```

If the PR has unresolved questions about API shape or migration choice, mark as **draft** and post the specific questions in the PR description.

After opening the PR: send the IFTTT notification (section 11).

---

## 7. Test failure handling

Reached when `:billing:test`, `:sample:assembleDebug`, or `:billing:lint` fails after the PBL bump.

### Step 1 — diagnose

Read the failure carefully. The default assumption is **the test is correct and the wrapper code is what needs to change**. PBL probably changed something the wrapper relied on.

Order of investigation:
1. **PBL behavior change.** Check the PBL release notes for the version range. Is there a behavior change that explains the failure?
2. **Wrapper internal code.** Does the wrapper code make an assumption that no longer holds?
3. **Test.** Only after #1 and #2 have been ruled out: is the test asserting something that's no longer true (i.e., the assertion itself is wrong, not just stale)?

If the conclusion is #3 (test is wrong), fix the test, but **explicitly note this in the PR body** so the maintainer can audit the test change.

### Step 2 — fix on the appropriate branch

If on Path A (current branch = `bump/pbl-<NEW>` from main):
- If the fix is internal-only (no public API touched) and confined to the bump branch — apply it, re-run tests, continue.
- If the fix requires public API changes — **the categorization flips to risky**:
  1. Push the WIP commit to a temporary location if useful (or just stash/cherry-pick).
  2. Delete the local `bump/pbl-<NEW>` branch.
  3. Switch to `next` (creating from `main` if missing). Create `bump/pbl-<NEW>-beta` from `next`.
  4. Reapply the bump + the fix on the new branch.
  5. Run verification again.
  6. Open the PR per Path B (section 6), explicitly noting in the body that the work was re-routed from Path A → Path B because the fix required public-API changes.

If on Path B (current branch = `bump/pbl-<NEW>-beta` from next):
- Apply the fix (public API changes are allowed here).
- Document each public-API change with a `**BREAKING:**` line in the PR body.
- Re-run verification.

### Step 3 — bound on attempts

Up to **3 fix attempts**. After three failed attempts (each with a meaningfully different approach):
- **Stop. Do not open a PR.**
- Open a **GitHub issue** instead, titled `[automated] PBL bump <OLD> → <NEW> needs maintainer intervention`. Include:
  - Each fix attempt's diff and outcome
  - The remaining failure output (full stack trace, full lint report, etc.)
  - The agent's best-guess hypothesis for what's wrong
  - A list of branches/commits the agent created during the attempt (so the maintainer can pick up where the agent left off)
- Send the IFTTT notification (section 11) pointing to the issue, not a PR.

---

## 8. CHANGELOG version-bump rules

The wrapper's semver is driven by **what changed in the wrapper's public API**, not by what changed in PBL. PBL is just the trigger.

- Wrapper public API unchanged AND no minSdk change → **patch** (e.g., `0.1.0` → `0.1.1`)
- Wrapper public API additive AND no minSdk change → **minor** (`0.1.0` → `0.2.0`)
- Wrapper public API broke OR minSdk changed → **major** (`0.x` → `1.0.0`)

For Path A (all-safe), it's always patch.

For Path B (risky), the agent picks based on the table above. Default is minor unless any `**BREAKING:**` items are listed in the PR body — then major.

`-beta` suffix is added on Path B PRs by default. The first beta is `-beta1`; subsequent attempts are `-beta2`, etc. The maintainer drops the suffix when cutting GA.

---

## 9. Branch lifecycle

- `main`: always represents the latest GA-shippable code. Path A PRs land here.
- `next`: tracks the in-progress next minor or major. Path B PRs land here. Once a `next`-cycle ships GA, fast-forward `main` to `next` and (optionally) leave `next` empty until the next risky bump arrives.
- `release/0.x`: created when v1 ships, for backports to the 0.x line. Out of scope for this playbook (manual maintenance).

The agent creates `next` if needed (first-time event); the agent never deletes branches.

---

## 10. Constraints (recap)

- No direct push to `main`
- No merge of any PR
- No tag creation
- No deps bumped other than `playBillingKtx`
- **No PR (draft or otherwise) with a failing build or failing tests.** Fix first; if you can't fix in 3 attempts, open an issue instead.
- **No duplicate bump PRs.** If an open `bump/pbl-*` PR exists, audit it (same target) or refresh it (stale target) per section 3 — never open a second one. **Single explicit exception:** when a stale-target refresh's new scope flips categorization Path A → Path B (section 3), the agent opens a fresh PR on `next` and leaves the old Path A PR open with a one-time `Superseded by #<NEW_PR>` comment for the maintainer to close. Subsequent daily runs must not repost that comment.
- **Read both release notes AND the migration guide** for every version in scope. Migration guide is required reading when a major boundary is crossed.
- **No auto-including net-new PBL features.** Open a feature-proposal issue and @-mention @kanetik (per section 4). Zero-ambiguity exception (Path B only): trivial overloads of methods the wrapper already wraps may be included in the bump PR, called out under "Net-new PBL surface included for parity." On Path A the exception is unavailable — even trivial overloads either go to an issue or flip the bump to Path B.
- Public API changes allowed only on `next`, never on `main`. If a Path A fix needs public API changes, re-route to Path B.
- Draft PRs are for *ambiguity*, not for failures. Post the specific questions in the PR description.
- When confidence < 80% on categorization, route as risky.

---

## 11. Notifications via IFTTT

After opening any PR or issue, the agent notifies the maintainer's Android device via IFTTT.

### Setup (one-time)

See [`ifttt-setup.md`](ifttt-setup.md) for the full step-by-step (applet creation, Webhooks trigger event name, Notifications action, phone-side test). At a glance:

- **Applet name:** `Kanetik PBL Update Notification` (case-sensitive — the playbook looks up by this exact name)
- **Trigger:** Webhooks → "Receive a web request with a JSON payload" → event name `kanetik_pbl_update`
- **Action:** Notifications → "Send a notification from the IFTTT app" → message `{{Value1}}`

The applet accepts a single text parameter via `{{Value1}}` — the playbook hands it the PR/issue URL plus a one-line categorization summary.

### Agent steps

1. Use `mcp__claude_ai_IFTTT__my_applets` to find an applet whose name matches `Kanetik PBL Update Notification` (or the name in the setup section above; if the applet is renamed, update both this playbook and the setup section).
2. If found: use `mcp__claude_ai_IFTTT__run_action` (or the equivalent) to fire the applet with text content:
   - For a PR: `Kanetik PBL update <OLD> -> <NEW> needs review: <PR_URL> [Path A | Path B-beta]`
   - For a target-version refresh (existing PR updated because a newer PBL stable is out, per section 3 — **not** audit fixes or rebase-only refreshes, which don't notify): `Kanetik PBL update PR refreshed <OLD_TARGET> -> <NEW_TARGET>: <PR_URL>`
   - For a feature-proposal issue (per section 4): `Kanetik PBL <VERSION> feature proposal needs decision: <ISSUE_URL>` — fire one per feature-proposal issue
   - For an intervention issue (3 fix attempts failed, per section 7): `Kanetik PBL update <OLD> -> <NEW> needs intervention: <ISSUE_URL>`
3. If not found, or if the IFTTT call fails: log the failure in a comment on the PR or issue ("notification not sent — applet not found / IFTTT error: <details>") and proceed. The PR/issue itself is the primary deliverable; the SMS is a convenience.

### Fallback

If IFTTT integration isn't available in the agent's runtime, GitHub's built-in PR/issue email notifications are the fallback. The maintainer still sees the work landed; just less promptly than via SMS.

---

## 12. The Routine prompt

Paste this into the Claude.ai Routine that runs daily. The prompt is fully self-contained — a fresh agent with no prior context can execute it.

```
Daily PBL update check for github.com/kanetik/billing-library.

You are invoked by a Claude.ai Routine on a daily schedule. Your job is to
keep the library's Play Billing Library pin in sync with Google's latest
stable release, while protecting consumers from breaking changes.

1. Fetch PBL's latest stable version:
   curl -fsSL https://dl.google.com/android/maven2/com/android/billingclient/billing-ktx/maven-metadata.xml \
     | grep -oE '<version>[^<]+</version>' | sed -E 's|</?version>||g' \
     | grep -vE '(alpha|beta|rc|dev|RC|preview|SNAPSHOT|M[0-9]+)' | tail -1

2. Fetch the wrapper's current pin:
   curl -fsSL https://raw.githubusercontent.com/kanetik/billing-library/main/gradle/libs.versions.toml \
     | grep '^playBillingKtx' | grep -oE '"[^"]+"' | tr -d '"'

3. If the two versions are equal: EXIT SILENTLY. No output, no log, no PR,
   no issue, no notification. Done.

4. If the wrapper is behind: clone the repo, then read the playbook at
   docs/_internal/automated-update-playbook.md and follow it end-to-end. The playbook
   covers:
   - **Checking for an already-open bump PR (section 3)** — if one exists,
     either audit/improve it in place (same target version) or refresh it
     to the new latest (stale target version). Never open a duplicate.
     Use `git push --force-with-lease` after any rebase (not `--force`).
   - **Reading BOTH the release notes AND the migration guide** for every
     version in scope (section 4). Migration guide is at
     https://developer.android.com/google/play/billing/migrate-<MAJOR>
     and is required reading when a major boundary is crossed.
   - Categorizing the release as safe vs risky
   - **Net-new PBL features get a feature-proposal issue, NOT inclusion in
     the bump PR (section 4).** Open one issue per feature, label it
     `question`, @-mention @kanetik, and leave the design call to the
     maintainer. Trivial overloads of already-wrapped methods are the
     only exception — and that exception is Path B only, because any
     new function in `com.kanetik.billing.*` is a public-API change.
   - Branching (main vs next, creating next if needed)
   - Editing libs.versions.toml + CHANGELOG.md
   - Running :billing:test, :sample:assembleDebug, :billing:lint
   - **Fixing failures rather than shipping a broken PR** (section 7)
   - Re-categorizing if the fix requires public-API changes
   - Opening a PR with templated title/body, OR opening a GitHub issue if
     the fix can't be completed in 3 attempts
   - Notifying the maintainer via IFTTT (section 11) — one ping per PR
     (new, or target-version refresh; audit fixes and rebase-only
     refreshes don't re-ping), one per feature-proposal issue, one
     per intervention issue
   - When to open as draft (ambiguity only — never as a workaround for
     failing tests)

Constraints — the playbook spells these out, but to be explicit:
- No push to main, no merge, no tag, no other dep bumps.
- No duplicate bump PRs. If a `bump/pbl-*` PR is already open, follow
  section 3: audit it (same target version) or refresh the existing
  branch to the new latest (stale target version). Never open a second
  PR for the same bump. Single documented exception (section 3 / §10):
  when a stale-target refresh's new scope flips categorization Path A
  → Path B, open a fresh PR on `next` and leave the old Path A PR
  open with a one-time, idempotent `Superseded by #<NEW_PR>` comment
  for the maintainer to close.
- No auto-including net-new PBL features in the bump PR. Open a
  `[feat proposal]` GitHub issue per feature, labeled `question`,
  @-mention @kanetik, and let the maintainer decide whether/how to
  expose it. Only exception (Path B only): trivial overloads of
  methods the wrapper already wraps. On Path A the exception does
  not apply — flip to Path B or open an issue.
- No PR (draft or otherwise) with a failing build or failing tests.
  Fix the underlying issue. After 3 fix attempts that don't work, open
  a GitHub issue instead and notify via IFTTT.
- Public API changes are allowed on `next` (Path B) but never on `main`
  (Path A). If a Path A fix turns out to need public-API changes, the
  categorization flips and the work moves to `next`.
- When fixing test failures, default assumption is the wrapper code is
  wrong, not the test. Investigation order: PBL behavior change ->
  wrapper internal code -> test.

Auth: gh CLI authenticated to kanetik/billing-library with PR-create and
issue-create permission.
Repo identity: configure git user.name + user.email to a bot/automation
identity (e.g., "kanetik-automation" / a no-reply email) so the commit
author is distinguishable from human commits.
IFTTT: section 11 of the playbook describes the applet-name lookup
mechanism. If the applet doesn't exist or IFTTT fails, fall back to
GitHub's built-in email notification (which fires automatically).
```

---

## 13. Manual-run mode (for testing the playbook)

To dry-run the playbook locally without waiting for the Routine:

1. Pretend the wrapper is on an older version. Either:
   - Temporarily edit `libs.versions.toml` to a known-old PBL pin, OR
   - Run against a fork / branch where the pin is older.
2. Run the same steps the Routine does manually (shell commands above).
3. Verify the agent opens the right PR against the right branch with the right body.
4. Verify the IFTTT notification fires.
5. Close the PR; revert any local edits.

Run this at least once after major edits to this playbook to make sure it still works end-to-end.

---

## 14. Maintenance of the playbook itself

If you change this playbook (rules, templates, branch strategy, IFTTT setup, terminology), update **both** the rules sections AND the Routine prompt block in section 12. The Routine prompt is the entry point; if it diverges from the rules, the agent's behavior diverges.

When in doubt: the agent always defers to the maintainer. Open a draft PR with the rationale (for ambiguity) or an issue (for failures it can't fix), and let the human decide.
