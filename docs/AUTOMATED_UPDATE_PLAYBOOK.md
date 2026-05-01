# Automated PBL update playbook

This is the playbook a Claude Code Web cron follows once a day to keep the library's Play Billing Library pin fresh. The cron itself is a single self-contained prompt (in section 9 below); it reads this doc and acts on it.

**Audience:** the agent firing on the cron, plus the human maintainer who reviews the PRs it opens.

**Purpose:** detect new stable PBL releases, categorize them as safe or risky, and open a high-quality PR against the correct branch for human review. The cron never auto-merges, never tags, never pushes to `main` directly.

---

## 1. Purpose & guardrails

### What the agent does

- Detects new PBL stable versions
- Categorizes each release as **safe** (bug fix / internal-only) or **risky** (API change / behavior change / minSdk bump)
- Opens a draft PR against `main` (safe) or `next` (risky)
- Includes categorization rationale in the PR body so the maintainer can review the reasoning
- Runs `:billing:test` and `:sample:assembleDebug` locally before opening the PR; refuses to open a non-draft PR if either fails

### What the agent never does

- Push directly to `main`
- Merge any PR
- Tag any release (`git tag` is a maintainer action only)
- Modify the **public API** of `com.kanetik.billing.*` — only adapts internal code to deprecations / removals
- Open a non-draft PR if any test or build fails — convert to draft and paste the failure into the PR body
- Touch any other dependencies in `libs.versions.toml` (PBL is the only one this playbook handles; other bumps are out of scope until the playbook is expanded)

If anything is ambiguous, the agent opens a draft PR with `[needs maintainer review of categorization]` in the title.

---

## 2. Detection

### Trigger

A scheduled run, daily by default.

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

- **Pinned == latest stable:** silent exit. No PR, no notification, no log message.
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

### Borderline → escalate (open as draft, flag in PR body)

- New deprecated API where the wrapper might want to migrate even though the old still works
- Anything labeled "important" or "behavior change" in release notes that's hard to categorize
- A change where the agent's confidence is < 80%

When unsure, route as risky. Cost of a false-risky is "human reviews a PR that could have been simpler"; cost of a false-safe is a regression on `main`.

### Multiple versions in scope

If multiple PBL versions accumulated since the last pin, take the **most-conservative** classification across all of them. If any version in the range is risky, the entire bump is risky.

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

### Verification

Before opening the PR, run locally:
```bash
./gradlew :billing:test :sample:assembleDebug :billing:lint
```

If any fail: do not open the PR. Open a draft instead with the failure pasted in the body.

### Open the PR

- **Title:** `build(deps): bump play-billing-ktx <OLD> → <NEW>`
- **Base branch:** `main`
- **Body:**
  ```
  Automated bump by daily PBL update cron.

  ## Version delta
  Pinned: <OLD>
  Latest stable: <NEW>
  Release notes: <URL>

  ## Categorization
  Safe — patch bump on main.

  Reasoning:
  - <bullet for each change in release notes, with the safe-rationale>

  ## Verification
  - :billing:test — <58 tests passed | N failed>
  - :sample:assembleDebug — <succeeded | failed>
  - :billing:lint — <clean | issues>

  ## CHANGELOG
  Stamped as `[<NEXT_PATCH_VERSION>] - <YYYY-MM-DD>`. Adjust the date at merge time if it slips.

  ## What the maintainer should do
  1. Skim the release notes link and confirm the categorization.
  2. Merge if happy.
  3. Tag `v<NEXT_PATCH_VERSION>` and push to trigger the publish workflow.
  ```

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
2. **Internal code adjustments only:**
   - If a deprecated API is still callable, leave the call site as-is and add a `// TODO(maintainer): migrate to <new API> per PBL <version>` comment.
   - If an API was *removed*, the agent makes the minimum-viable migration to keep the wrapper compiling. Detailed adaptation choices belong to the maintainer; the agent doesn't redesign — it preserves intent.
   - **Never change `com.kanetik.billing.*` public API in this PR.** Public-API changes are a separate maintainer-driven follow-up.
3. `CHANGELOG.md` on the `next` branch: add an entry under `## [Unreleased]`:
   ```markdown
   ### Changed

   - **(beta)** Bumped Play Billing Library `<OLD>` → `<NEW>` ([release notes](<URL>)).
     <summary>

   ### Risky items flagged for follow-up

   - <bullet for each risky item, with link to release-notes section and a TODO marker>
   ```
4. **Do not** modify `[Unreleased]` on `main` for this work.

### Verification

Same as Path A: `./gradlew :billing:test :sample:assembleDebug :billing:lint` before opening the PR.

If anything fails, open as **draft** with the failure pasted into the body.

### Open the PR

- **Title:** `build(deps): bump play-billing-ktx <OLD> → <NEW> (beta — risky changes flagged)`
- **Base branch:** `next`
- **Body:**
  ```
  Automated bump by daily PBL update cron. Routed to `next` because the
  release contains changes that warrant maintainer review before merging
  to `main`.

  ## Version delta
  Pinned: <OLD>
  Latest stable: <NEW>
  Release notes: <URL>

  ## Categorization
  Risky — minor or major beta on `next`.

  ### Risky items
  - <each risky change with: what it is, why it's risky, what the wrapper needs to do>

  ### Safe items (along for the ride)
  - <each safe change>

  ## Suggested wrapper version
  <NEXT_VERSION>-beta1
  Rationale: <why minor or major + why beta>

  ## Branch creation note (if applicable)
  This PR is the first time the `next` branch was used. Created from `main` at
  commit <sha> on <date>.

  ## Verification
  - :billing:test — <result>
  - :sample:assembleDebug — <result>
  - :billing:lint — <result>

  ## What the maintainer should do
  1. Read the risky items list and decide migration strategy for each TODO.
  2. Possibly: update wrapper's public API to expose new PBL features (separate PR
     against `next`).
  3. Merge to `next` when satisfied with the bump itself.
  4. Tag a `vX.Y.Z-beta1` release from `next` to publish the beta.
  5. After dog-fooding the beta, fast-forward `main` to `next` and tag `vX.Y.Z` GA.
  ```

If the PR has a TODO that the agent couldn't resolve confidently, mark the PR as **draft**.

---

## 6. CHANGELOG version-bump rules

The wrapper's semver is driven by **what changed in the wrapper's public API**, not by what changed in PBL. PBL is just the trigger.

- Wrapper public API unchanged AND no minSdk change → **patch** (e.g., `0.1.0` → `0.1.1`)
- Wrapper public API additive AND no minSdk change → **minor** (`0.1.0` → `0.2.0`)
- Wrapper public API broke OR minSdk changed → **major** (`0.x` → `1.0.0`)

For Path A (all-safe), it's always patch.

For Path B (risky), the agent picks based on the table above. Default is minor unless minSdk changes (then major). The maintainer can override at merge time.

`-beta` suffix is added on Path B PRs by default. The first beta is `-beta1`; subsequent attempts are `-beta2`, etc. The maintainer drops the suffix when cutting GA.

---

## 7. Branch lifecycle

- `main`: always represents the latest GA-shippable code. Path A PRs land here.
- `next`: tracks the in-progress next minor or major. Path B PRs land here. Once a `next`-cycle ships GA, fast-forward `main` to `next` and (optionally) leave `next` empty until the next risky bump arrives.
- `release/0.x`: created when v1 ships, for backports to the 0.x line. Out of scope for this playbook (manual maintenance).

The agent creates `next` if needed (first-time event); the agent never deletes branches.

---

## 8. Constraints (recap)

- No direct push to `main`
- No merge of any PR
- No tag creation
- No public API changes in `com.kanetik.billing.*`
- No bumps to deps other than `playBillingKtx`
- No non-draft PR if tests fail
- When confidence < 80%, route as risky and/or open as draft

---

## 9. The cron prompt

Paste this into Claude Code Web's `/schedule` (or equivalent scheduler) with a daily cadence. The prompt is fully self-contained: a fresh agent with no prior context can execute it.

```
Daily PBL update check for github.com/kanetik/billing-library.

1. Fetch PBL's latest stable version:
   curl -fsSL https://dl.google.com/android/maven2/com/android/billingclient/billing-ktx/maven-metadata.xml \
     | grep -oE '<version>[^<]+</version>' | sed -E 's|</?version>||g' \
     | grep -vE '(alpha|beta|rc|dev|RC|preview|SNAPSHOT|M[0-9]+)' | tail -1

2. Fetch the wrapper's current pin:
   curl -fsSL https://raw.githubusercontent.com/kanetik/billing-library/main/gradle/libs.versions.toml \
     | grep '^playBillingKtx' | grep -oE '"[^"]+"' | tr -d '"'

3. If the two versions are equal: EXIT SILENTLY. No output, no log, no PR. Done.

4. If the wrapper is behind: clone the repo, then read the playbook at
   docs/AUTOMATED_UPDATE_PLAYBOOK.md and follow it end-to-end. The playbook covers:
   - Categorizing the release as safe vs risky
   - Branching (main vs next, creating next if needed)
   - Editing libs.versions.toml + CHANGELOG.md
   - Running :billing:test, :sample:assembleDebug, :billing:lint
   - Opening a PR with the templated title/body
   - When to open as draft instead of ready-for-review

Constraints — the playbook spells these out, but to be explicit:
- Do not push to main, merge any PR, tag any release, or change the public API.
- Do not bump any dep other than playBillingKtx.
- If tests fail, open a draft PR with the failure pasted into the body.
- If categorization confidence is < 80%, route as risky and open as draft.

Auth: gh CLI authenticated to kanetik/billing-library with PR-create permission.
Repo identity: configure git user.name + user.email to a bot/automation
identity (e.g., "kanetik-automation" / a no-reply email) so the commit
author is distinguishable from human commits.
```

---

## 10. Manual-run mode (for testing the playbook)

To dry-run the playbook locally without waiting for the cron:

1. Pretend the wrapper is on an older version. Either:
   - Temporarily edit `libs.versions.toml` to a known-old PBL pin, OR
   - Run against a fork / branch where the pin is older.
2. Run the same steps the cron does manually (shell commands above).
3. Verify the agent opens the right PR against the right branch.
4. Close the PR; revert any local edits.

Run this at least once after major edits to this playbook to make sure it still works end-to-end.

---

## 11. Maintenance of the playbook itself

If you change this playbook (rules, templates, branch strategy), update **both** the rules section AND the cron prompt block in section 9. The cron prompt is the entry point; if it diverges from the rules, the agent's behavior diverges.

When in doubt: the agent always opens a draft PR with the rationale, and the human is the final arbiter.
