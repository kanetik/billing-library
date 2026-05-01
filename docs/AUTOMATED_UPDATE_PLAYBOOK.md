# Automated PBL update playbook

This playbook is invoked by a **Claude.ai Routine** on a daily schedule. The Routine fires the prompt in section 11 below; that prompt reads this doc and acts on it.

**Audience:** the agent firing on the Routine, plus the human maintainer who reviews the PRs (and occasionally the issues) it opens.

**Purpose:** detect new stable PBL releases, categorize them as safe or risky, fix anything the bump breaks in the wrapper, and open a high-quality PR against the correct branch for human review. The agent never auto-merges, never tags, never pushes to `main` directly.

---

## 1. Purpose & guardrails

### What the agent does

- Detects new PBL stable versions
- Categorizes each release as **safe** (bug fix / internal-only) or **risky** (API change / behavior change / minSdk bump)
- Fixes any code or test failures the bump introduces, on the appropriate branch
- Opens a PR against `main` (safe) or `next` (risky), with categorization rationale and a clear summary of what changed
- Notifies the maintainer via IFTTT (SMS to Android) when the PR is ready

### What the agent never does

- Push directly to `main`
- Merge any PR
- Tag any release
- Bump any dependency other than `playBillingKtx`
- **Open a PR (draft or otherwise) with a failing build or failing tests.** This is absolute. If the bump breaks something, the agent fixes it before opening the PR. If it can't fix in three attempts, it opens an *issue* instead, with full diagnostic detail.

### Working-assumption when something fails

When a test fails after the bump, **the default assumption is that the test is correct and the wrapper code (or the PBL behavior the wrapper relied on) is what needs to change**. The test failure is the bump's signal that something material shifted. Don't reach for the test as the cause unless the wrapper-side investigation rules everything else out.

It's not impossible for the test to be at fault — but the order of investigation is: PBL behavior change → wrapper internal code → test, in that order. If the test really is wrong, fixing it is fine, but the PR body should explicitly call that out so the maintainer knows.

### Public API changes

- **On `main` (Path A):** never. If the bump turns out to require any change to types or signatures in `com.kanetik.billing.*`, the categorization flips from safe to risky and the work moves to `next`.
- **On `next` (Path B):** allowed and expected. Risky bumps may require new types, removed types, or signature changes in `com.kanetik.billing.*` to absorb upstream PBL changes cleanly. The PR body must enumerate every public-API change with a "**BREAKING:**" prefix so reviewers can audit them in one place.

### Draft PRs are for *ambiguity*, not for failures

A draft PR is the right tool when the agent's categorization confidence is below ~80%, when a migration choice has multiple reasonable paths, or when the agent is uncertain about a public-API decision on `next`. In those cases the agent opens a draft and posts its specific questions in the PR description (and follows up in PR comments if more come up while writing).

A draft PR is **not** acceptable as a way to ship work with failing tests or a broken build. If something's failing, the agent fixes it (section 6) or opens an issue.

---

## 2. Detection

### Trigger

The Claude.ai Routine fires the prompt in section 11 daily.

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

## 3. Categorization rubric

Read each version's release notes from <https://developer.android.com/google/play/billing/release-notes>. Classify each change.

### Safe (route to `main`, wrapper patch bump)

- Pure bug fix
- Performance improvement
- Internal refactor (PBL's own internals, no consumer-visible change)
- New PBL API the wrapper does **not** expose and doesn't need to expose
- Documentation-only change

### Risky (route to `next`, wrapper minor or major beta)

- **Any deprecation** of an API the wrapper uses internally (`grep` the wrapper for the deprecated symbol — if found, route as risky even if it still compiles)
- **Any removal** of an API
- **Behavior change** of an existing API (release notes say "behavior change", "now returns", "changed semantics", etc.)
- **New minSdk requirement** (e.g., PBL 8.1 raised the floor from 21 to 23)
- **New permission** required in the manifest
- **New build-script requirement** (Kotlin/AGP/Gradle minimum version)
- **New PBL API the wrapper should arguably expose** — adds public API surface to the wrapper, which is a minor bump by semver

### Borderline → escalate (open as draft, flag in PR body with explicit questions)

- New deprecated API where the wrapper might want to migrate even though the old still works
- Anything labeled "important" or "behavior change" in release notes that's hard to categorize
- A change where the agent's confidence is < 80%

When unsure, route as risky. Cost of a false-risky is "human reviews a PR that could have been simpler"; cost of a false-safe is a regression on `main`.

### Multiple versions in scope

If multiple PBL versions accumulated since the last pin, take the **most-conservative** classification across all of them. If any version in the range is risky, the entire bump is risky.

### Re-categorization mid-flow

If the agent starts on Path A (safe) and discovers during the fix loop that the necessary fix requires public-API changes, the categorization **flips to risky**. Abandon the `main` branch work, switch to `next`, and follow Path B. Section 6 covers this transition explicitly.

---

## 4. Path A — all-safe bump (target `main`)

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

If anything fails: do **not** open a PR. Go to section 6 (Test failure handling).

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

  ## Categorization
  Safe — patch bump on main.

  Reasoning:
  - <bullet for each change in release notes, with the safe-rationale>

  ## Verification
  - :billing:test — 58 tests passed
  - :sample:assembleDebug — succeeded
  - :billing:lint — clean

  ## CHANGELOG
  Stamped as `[<NEXT_PATCH_VERSION>] - <YYYY-MM-DD>`. Adjust the date at merge time if it slips.

  ## What the maintainer should do
  1. Skim the release notes link and confirm the categorization.
  2. Merge if happy.
  3. Tag `v<NEXT_PATCH_VERSION>` and push to trigger the publish workflow.
  ```

After opening the PR: send the IFTTT notification (section 10).

---

## 5. Path B — risky bump (target `next`)

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
   - If the bump enables a new PBL feature the wrapper should expose, the agent **may** add the new surface, but should open as a **draft** and ask the maintainer to confirm the API shape before going non-draft.
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

Same as Path A: `./gradlew :billing:test :sample:assembleDebug :billing:lint`. If anything fails, go to section 6.

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
  3. Merge to `next` when satisfied with the bump itself.
  4. Tag a `vX.Y.Z-beta1` release from `next` to publish the beta.
  5. After dog-fooding the beta, fast-forward `main` to `next` and tag `vX.Y.Z` GA.
  ```

If the PR has unresolved questions about API shape or migration choice, mark as **draft** and post the specific questions in the PR description.

After opening the PR: send the IFTTT notification (section 10).

---

## 6. Test failure handling

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
  6. Open the PR per Path B (section 5), explicitly noting in the body that the work was re-routed from Path A → Path B because the fix required public-API changes.

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
- Send the IFTTT notification (section 10) pointing to the issue, not a PR.

---

## 7. CHANGELOG version-bump rules

The wrapper's semver is driven by **what changed in the wrapper's public API**, not by what changed in PBL. PBL is just the trigger.

- Wrapper public API unchanged AND no minSdk change → **patch** (e.g., `0.1.0` → `0.1.1`)
- Wrapper public API additive AND no minSdk change → **minor** (`0.1.0` → `0.2.0`)
- Wrapper public API broke OR minSdk changed → **major** (`0.x` → `1.0.0`)

For Path A (all-safe), it's always patch.

For Path B (risky), the agent picks based on the table above. Default is minor unless any `**BREAKING:**` items are listed in the PR body — then major.

`-beta` suffix is added on Path B PRs by default. The first beta is `-beta1`; subsequent attempts are `-beta2`, etc. The maintainer drops the suffix when cutting GA.

---

## 8. Branch lifecycle

- `main`: always represents the latest GA-shippable code. Path A PRs land here.
- `next`: tracks the in-progress next minor or major. Path B PRs land here. Once a `next`-cycle ships GA, fast-forward `main` to `next` and (optionally) leave `next` empty until the next risky bump arrives.
- `release/0.x`: created when v1 ships, for backports to the 0.x line. Out of scope for this playbook (manual maintenance).

The agent creates `next` if needed (first-time event); the agent never deletes branches.

---

## 9. Constraints (recap)

- No direct push to `main`
- No merge of any PR
- No tag creation
- No deps bumped other than `playBillingKtx`
- **No PR (draft or otherwise) with a failing build or failing tests.** Fix first; if you can't fix in 3 attempts, open an issue instead.
- Public API changes allowed only on `next`, never on `main`. If a Path A fix needs public API changes, re-route to Path B.
- Draft PRs are for *ambiguity*, not for failures. Post the specific questions in the PR description.
- When confidence < 80% on categorization, route as risky.

---

## 10. Notifications via IFTTT

After opening any PR or issue, the agent notifies the maintainer's Android device via IFTTT.

### Setup (one-time)

The maintainer creates an IFTTT applet with these properties:
- **Name:** `Kanetik PBL Update Notification` (or any consistent name; the playbook references it by name)
- **Trigger:** Anything that the Claude.ai IFTTT integration can fire (typically a webhook trigger or a Claude-side action)
- **Action:** Send SMS to the maintainer's Android device, or "Send Android notification" — whichever IFTTT-supported action goes to the maintainer's phone

The applet body should accept a single text parameter — the agent will pass the PR URL + a one-line categorization summary.

### Agent steps

1. Use `mcp__claude_ai_IFTTT__my_applets` to find an applet whose name matches `Kanetik PBL Update Notification` (or the name in the setup section above; if the applet is renamed, update both this playbook and the setup section).
2. If found: use `mcp__claude_ai_IFTTT__run_action` (or the equivalent) to fire the applet with text content:
   - For a PR: `Kanetik PBL update <OLD> -> <NEW> needs review: <PR_URL> [Path A | Path B-beta]`
   - For an issue: `Kanetik PBL update <OLD> -> <NEW> needs intervention: <ISSUE_URL>`
3. If not found, or if the IFTTT call fails: log the failure in a comment on the PR or issue ("notification not sent — applet not found / IFTTT error: <details>") and proceed. The PR/issue itself is the primary deliverable; the SMS is a convenience.

### Fallback

If IFTTT integration isn't available in the agent's runtime, GitHub's built-in PR/issue email notifications are the fallback. The maintainer still sees the work landed; just less promptly than via SMS.

---

## 11. The Routine prompt

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
   docs/AUTOMATED_UPDATE_PLAYBOOK.md and follow it end-to-end. The playbook
   covers:
   - Categorizing the release as safe vs risky
   - Branching (main vs next, creating next if needed)
   - Editing libs.versions.toml + CHANGELOG.md
   - Running :billing:test, :sample:assembleDebug, :billing:lint
   - **Fixing failures rather than shipping a broken PR** (section 6)
   - Re-categorizing if the fix requires public-API changes
   - Opening a PR with templated title/body, OR opening a GitHub issue if
     the fix can't be completed in 3 attempts
   - Notifying the maintainer via IFTTT (section 10)
   - When to open as draft (ambiguity only — never as a workaround for
     failing tests)

Constraints — the playbook spells these out, but to be explicit:
- No push to main, no merge, no tag, no other dep bumps.
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
IFTTT: section 10 of the playbook describes the applet-name lookup
mechanism. If the applet doesn't exist or IFTTT fails, fall back to
GitHub's built-in email notification (which fires automatically).
```

---

## 12. Manual-run mode (for testing the playbook)

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

## 13. Maintenance of the playbook itself

If you change this playbook (rules, templates, branch strategy, IFTTT setup, terminology), update **both** the rules sections AND the Routine prompt block in section 11. The Routine prompt is the entry point; if it diverges from the rules, the agent's behavior diverges.

When in doubt: the agent always defers to the maintainer. Open a draft PR with the rationale (for ambiguity) or an issue (for failures it can't fix), and let the human decide.
