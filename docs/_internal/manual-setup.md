# Manual setup — Maven Central publishing

This is the one-time setup that gets `com.kanetik.billing:billing` onto Maven Central. None of it can be automated by Claude Code or the build itself — it requires Sonatype account access, DNS records on `kanetik.com`, GPG key generation, and GitHub repository secrets.

After all these steps are done, day-to-day publishing is automated: push a tag like `v0.1.0`, the `publish.yml` workflow uploads to Sonatype Central Portal staging, and you click **Release** in the Portal UI to make it public.

---

## 1. Sonatype Central Portal account + namespace

### 1.1 Create the account

1. Go to <https://central.sonatype.com>.
2. Sign up with GitHub OAuth (uses your `kanetik` GitHub identity directly).
3. Verify the email Sonatype sends.

### 1.2 Register the `com.kanetik` namespace

1. In the Portal, **Namespaces → Add Namespace**.
2. Enter `com.kanetik` (not `com.kanetik.billing` — the namespace is the top-level prefix; you can publish any artifact starting with `com.kanetik.*` from one namespace).
3. Sonatype will display a verification code, e.g. `abc123def456`.

### 1.3 Verify the namespace via DNS TXT record

This proves you own `kanetik.com`.

1. Log in to your DNS provider for `kanetik.com`.
2. Add a TXT record at the apex (`@`):
   - **Type**: TXT
   - **Name / Host**: `@` (or leave blank — provider-dependent)
   - **Value**: `<verification-code-from-sonatype>` (just the code, no `sonatype-central-verification=` prefix unless your provider requires it)
   - **TTL**: 3600 (1 hour) is fine; lower means faster propagation.
3. Wait 5–60 minutes for propagation. Verify with `nslookup -type=TXT kanetik.com` (Windows) or `dig TXT kanetik.com` (Linux/macOS).
4. Back in the Portal, click **Verify Namespace**. Once green, the namespace is yours.

### 1.4 Generate a publishing user token

The token replaces username/password for publishing — Sonatype no longer accepts the raw password.

1. Portal → **Account → Generate User Token**.
2. Copy both the **token username** and **token password** (they look like long random strings).
3. Stash them in your **global** Gradle properties — `~/.gradle/gradle.properties` on macOS/Linux, `C:\Users\<you>\.gradle\gradle.properties` on Windows. This is the per-user config outside any project, so secrets stay out of git. Create the file if it doesn't exist; the `.gradle` folder itself appears the first time you run any Gradle command.
   ```properties
   mavenCentralUsername=<token-username>
   mavenCentralPassword=<token-password>
   ```
   (Don't confuse this with `<project>/gradle.properties` — that one is committed to git and holds project-wide config like the AndroidX flag.)
4. These same values also become the GitHub Actions secrets `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` in step 4 below.

---

## 2. GPG key for artifact signing

Maven Central rejects unsigned artifacts. You need a GPG key whose public half is on a public keyserver, and whose private half is available to Gradle.

### 2.0 Make sure `gpg` is callable

- **macOS**: `brew install gnupg` (or it's already there if you've used Homebrew git).
- **Linux**: `apt install gnupg` / `dnf install gnupg2` — usually pre-installed.
- **Windows**: You probably already have it. Git for Windows ships `gpg.exe` at `C:\Program Files\Git\usr\bin\gpg.exe`. Two options:
  - **Use Git Bash** for all section-2 commands — fastest path, no install needed. The bundled gpg works as-is.
  - **Install [Gpg4win](https://www.gpg4win.org/)** for a native PowerShell experience plus the Kleopatra GUI for key management. Recommended if you'll be publishing from this machine for the long term.

  PowerShell alone (without Gpg4win or Git for Windows on PATH) will give you `gpg: The term 'gpg' is not recognized...`. That's the trigger to pick one of the above.

### 2.1 Generate the key

```bash
gpg --full-generate-key
```

Settings:
- **Type**: RSA and RSA (default)
- **Size**: 4096
- **Expiration**: `0` (no expiry) or `2y` (2 years — easier to rotate; you'd need to re-export and re-publish then)
- **Real name**: `Kanetik`
- **Email**: `billinglibrary@kanetik.com`
- **Comment**: leave blank
- **Passphrase**: long, random, stored in your password manager

### 2.2 Find the key ID

```bash
gpg --list-secret-keys --keyid-format=long
```

Output looks like:
```
sec   rsa4096/<KEYID> 2026-04-30 [SC]
      <FINGERPRINT>
uid                 [ultimate] Kanetik <billinglibrary@kanetik.com>
ssb   rsa4096/<SUBKEYID> 2026-04-30 [E]
```

The 16-char hex string after `rsa4096/` is the long key ID. The 8-char suffix is the short ID.

### 2.3 Publish the public half to keyservers

Maven Central's signature verifier resolves keys via multiple public keyservers and accepts the key from whichever serves it first. The two we care about behave very differently:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEYID>
gpg --keyserver keys.openpgp.org --send-keys <LONG_KEYID>
```

| Keyserver | Verification model | Practical effect |
|---|---|---|
| `keyserver.ubuntu.com` | None — accepts any upload silently | The key is immediately resolvable. **This is sufficient for Maven Central signature validation.** |
| `keys.openpgp.org` | Email confirmation required for each UID | Without verification, the key material is uploaded but UIDs aren't bound — the key won't show up in email-based searches. |

#### Critical path: keyserver.ubuntu.com only

For Maven Central publishing, `keyserver.ubuntu.com` having the key is enough — Sonatype's verifier finds it there and validates the signature. You can proceed with the rest of the manual steps even if `keys.openpgp.org` verification is incomplete.

#### Best-practice (not blocking): verify on keys.openpgp.org

`keys.openpgp.org` sends a verification email to each UID on the key. Check `billinglibrary@kanetik.com` for a message from `noreply@keys.openpgp.org` and click the link.

If the email doesn't arrive after 30+ minutes, here's what's usually going on:

- **Spam folder.** Catch-all domains (any `*@kanetik.com` lands in one mailbox) are a common case — they get filtered aggressively because they collect a lot of junk. Check spam, spam quarantine, and any "blocked senders" reports.
- **Greylisting.** Many mail servers temporarily reject first-time senders; legit senders retry 15–60 min later. Wait it out.
- **SPF/DKIM/DMARC strictness.** If your mail server fails authentication on the keys.openpgp.org sender, the message gets silently rejected. Server logs (if you have access) show this.
- **Use a different UID.** If your domain just won't accept this mail, add a Gmail/etc. UID to the key and verify via that:
  ```bash
  gpg --quick-add-uid <LONG_KEYID> "Kanetik <some-other-address@example.com>"
  gpg --keyserver keys.openpgp.org --send-keys <LONG_KEYID>
  ```

If you can't get verification through, **don't block on it** — `keyserver.ubuntu.com` covers the Maven Central path. Revisit later if you find time, or if Sonatype ever surfaces a "key not found" error specifically tied to keys.openpgp.org.

### 2.4 Make the private half available to Gradle (local)

For local publishing (`./gradlew :billing:publishToMavenCentral`), add to the same **global** Gradle properties file from section 1.4 — `~/.gradle/gradle.properties` (macOS/Linux) or `C:\Users\<you>\.gradle\gradle.properties` (Windows):

```properties
signing.keyId=<LAST_8_CHARS_OF_LONG_KEYID>
signing.password=<gpg-passphrase>
# macOS / Linux:
signing.secretKeyRingFile=/Users/<you>/.gnupg/secring.gpg
# Windows (forward slashes — see note below):
signing.secretKeyRingFile=C:/Users/<you>/.gnupg/secring.gpg
```

Modern GPG (2.1+) doesn't write `secring.gpg` automatically; you need to export it once:

```bash
gpg --export-secret-keys <LONG_KEYID> > ~/.gnupg/secring.gpg
```

Windows path notes (these tripped me up the first time):
- The keyring file lives at `C:\Users\<you>\.gnupg\secring.gpg`.
- In `gradle.properties`, write the path **starting with the drive letter and using forward slashes** — `C:/Users/jkane/.gnupg/secring.gpg`. Gradle (a JVM, not bash) does not understand the Git Bash `/c/Users/...` shorthand; it treats that as a *relative* path and resolves it against the project root, producing nonsense like `C:\Users\jkane\Projects\billing-library\billing\c\Users\jkane\.gnupg\secring.gpg`.
- Backslashes also work, but must be doubled (`C:\\Users\\jkane\\.gnupg\\secring.gpg`) since `.properties` files treat `\` as an escape character. Forward slashes are simpler.

### 2.5 Make the private half available to GitHub Actions

The `publish.yml` workflow expects an **ASCII-armored** private key as a secret (no file path — secrets are env vars).

```bash
gpg --armor --export-secret-keys <LONG_KEYID> > /tmp/signing-key.asc
```

Where the file lands:
- macOS / Linux: `/tmp/signing-key.asc`
- Windows + Git Bash: `C:\Users\<you>\AppData\Local\Temp\signing-key.asc` (Git Bash's `/tmp` maps to `%LOCALAPPDATA%\Temp`)
- PowerShell users: `gpg --armor --export-secret-keys <LONG_KEYID> > $env:TEMP\signing-key.asc` is the equivalent invocation.

Copy the **full file contents** — including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----` lines — into the `SIGNING_KEY` GitHub Actions secret in step 4 below.

Quick clipboard helper from Git Bash: `cat /tmp/signing-key.asc | clip`.

After it's in GitHub, **delete the on-disk copy**:
```bash
rm /tmp/signing-key.asc
```
The runner doesn't need it, and it's a plaintext private key sitting in your temp folder.

---

## 3. Verify the email account

`billinglibrary@kanetik.com` is the developer email in the published POM. Confirm:

1. The mailbox exists and you can receive mail.
2. The address isn't a typo on `kanetik.com`'s mail config.

Test by sending yourself a message from any other account, or by relying on `keys.openpgp.org` — its verification email goes to that address (step 2.3).

---

## 4. GitHub Actions secrets

In the `kanetik/billing-library` repo → **Settings → Secrets and variables → Actions → New repository secret**, add four secrets:

| Name | Value | Source |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | Token username | Section 1.4 |
| `MAVEN_CENTRAL_PASSWORD` | Token password | Section 1.4 |
| `SIGNING_KEY` | ASCII-armored private key | Section 2.5 (full file contents incl. BEGIN/END lines) |
| `SIGNING_PASSWORD` | GPG passphrase | Section 2.1 |

These are read by `.github/workflows/publish.yml` and exposed to Gradle as `ORG_GRADLE_PROJECT_*` env vars. The plugin's "in-memory key" mode means `SIGNING_KEY` is decoded straight into the JVM — no key file ever touches the runner's disk.

---

## 5. First publish — local dry-run

Before tagging, do a local publish to staging. This is lower-risk than CI for the first attempt: any GPG/credential issue surfaces on your machine where you can debug it, not in a workflow log.

```bash
cd C:/Users/jkane/Projects/billing-library
./gradlew :billing:publishToMavenCentral -PVERSION_NAME=0.1.0-beta1 --no-configuration-cache --stacktrace
```

The task uploads to a **staging repository** on the Central Portal. It does NOT make the artifact public yet.

### 5.1 Verify in the Portal UI

1. <https://central.sonatype.com/publishing/deployments>
2. Find your deployment (named after the timestamp + namespace).
3. Click in. Sonatype validates the upload (signature, POM completeness, sources/javadoc presence). Status will be **VALIDATED** if all checks pass, **FAILED** with a reason if not.

### 5.2 Release to public

If validation is green:
1. Click **Publish** in the Portal UI.
2. Wait 10–30 min for the artifact to propagate to <https://repo.maven.apache.org/maven2/com/kanetik/billing/billing/>.
3. Search-index propagation (so it shows up in <https://central.sonatype.com>'s search) takes a few hours additional.

### 5.3 If validation fails

Common causes and fixes:

| Failure | Fix |
|---|---|
| "Missing signature for `.aar`" | `signAllPublications()` didn't run; check `signing.*` properties in `~/.gradle/gradle.properties`. |
| "Public key not found in keyserver" | Re-push to `keyserver.ubuntu.com` (the one Maven Central actually relies on); wait 30 min for propagation. `keys.openpgp.org` is best-practice but optional — see section 2.3. |
| "POM missing `description` / `developers` / `scm`" | Edit `billing/build.gradle.kts` `mavenPublishing { pom { ... } }` block. |
| "Snapshot version on release endpoint" | You forgot `-PVERSION_NAME=0.1.0-...`; the default `0.1.0-SNAPSHOT` only goes to the snapshot repo, which Central Portal doesn't host. |
| "Namespace not verified" | DNS TXT record hasn't propagated, or it's on the wrong record name. Re-check section 1.3. |

You can drop a failed staging deployment in the Portal UI and re-upload after fixing.

---

## 6. Test in a real consumer before going public

The dry-run from section 5 validates the publishing pipeline (signing, POM, upload). It does not validate that the library actually works when a consumer pulls it in. For that, do an integration round-trip from your local machine — no Maven Central involvement, no public exposure.

Why: once anything is on Maven Central it's there forever (Sonatype doesn't allow unpublishing). A `-betaN` suffix is a *social* signal that the version is unstable, not a privacy gate — anyone who explicitly types the exact version string can pull a beta. So the only way to test "for real" without risking public consumption is to never publish until you're confident.

### 6.1 Drop any prior staging deployment

If you ran section 5 and the staging deployment is still sitting in `VALIDATED` state, **don't click Publish**. Click **Drop** instead. The artifact stays out of public Central; the deployment is gone.

### 6.2 Publish to your local Maven cache

```bash
cd <billing-library>
./gradlew :billing:publishToMavenLocal
```

The artifact lands in `~/.m2/repository/com/kanetik/billing/billing/0.1.0-SNAPSHOT/` (Windows: `C:\Users\<you>\.m2\repository\com\kanetik\billing\billing\0.1.0-SNAPSHOT\`). Visible only to your user account; nothing leaves the machine.

### 6.3 Wire `mavenLocal()` into the consumer

In the consumer project's `settings.gradle.kts`, add `mavenLocal()` first inside `dependencyResolutionManagement.repositories { ... }` so Gradle prefers the local copy over Central:

```kotlin
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()       // <- add this, first
        google()
        mavenCentral()
    }
}
```

### 6.4 Declare the dependency

In the consumer's `gradle/libs.versions.toml`:

```toml
[versions]
kanetikBilling = "0.1.0-SNAPSHOT"

[libraries]
kanetik-billing = { module = "com.kanetik.billing:billing", version.ref = "kanetikBilling" }
```

In the consumer's app module:

```kotlin
implementation(libs.kanetik.billing)
```

### 6.5 Iterate

Each round trip:
1. Edit library code.
2. `./gradlew :billing:publishToMavenLocal` (~15s).
3. Rebuild the consumer; new bits picked up automatically.

Once the library does what you want, remove `mavenLocal()` from the consumer's repo list (so future builds resolve from Central) and proceed to section 7 to publish for real.

### 6.6 Alternative: composite build (`includeBuild`)

If you want a tighter dev loop without a `publishToMavenLocal` step, use Gradle's composite-build feature in the consumer:

```kotlin
// consumer's settings.gradle.kts
includeBuild("../billing-library") {
    dependencySubstitution {
        substitute(module("com.kanetik.billing:billing")).using(project(":billing"))
    }
}
```

Pros: instant change propagation; no publish step.

Cons: skips the actual artifact-resolution path — the consumer reads source/project output instead of a parsed POM + AAR. You can ship a bug that only manifests against the published artifact (POM dependency-scope mismatches, consumer-rules.pro not being applied, etc.).

Recommendation: use composite build for *active library development* (when you're iterating on the library), and switch back to `mavenLocal` for the actual cutover validation before publishing.

---

## 7. Tag-driven publish (steady state)

Once you're confident the library works in a real consumer (section 6) and the publishing infrastructure dry-ran cleanly (section 5), the steady-state release workflow is:

1. Bump version in `CHANGELOG.md` (the `[Unreleased]` → `[0.1.0] - YYYY-MM-DD` rename).
2. Commit + push to `main`.
3. Tag and push:
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
4. The `publish.yml` workflow runs automatically, uploads to staging.
5. Click **Publish** in the Portal UI (the v0.x manual-gate policy — switch to `publishAndReleaseToMavenCentral` once the pipeline is trusted).

For betas: tag as `v0.1.0-beta1`, `v0.1.0-beta2`, etc. The workflow strips the leading `v` and passes the suffix through unchanged. Consumers opt into betas by exact-version match (`implementation("com.kanetik.billing:billing:0.1.0-beta1")`).

---

## 8. Future: switching to auto-release

After v0.1.0 ships and the pipeline is trusted, change `billing/build.gradle.kts`:

```kotlin
publishToMavenCentral(automaticRelease = true)
```

…or replace the `publishToMavenCentral` task with `publishAndReleaseToMavenCentral` in `.github/workflows/publish.yml`. Either flips the workflow from "upload to staging" to "upload + release in one step" — no manual Portal click needed.

---

## Troubleshooting

- **`./gradlew: Permission denied` on Linux runners**: the gradle-wrapper script lost its executable bit on Windows. Fix once with `git update-index --chmod=+x gradlew && git commit -m "ci: chmod +x gradlew"`. (Already handled in this repo's git index.)
- **GPG key passphrase prompt during local publish**: pass `--quiet` to suppress, or set `signing.password` in `gradle.properties` (already documented in 2.4).
- **Signed-but-rejected for "validation failed: pom"**: open the failed deployment in the Portal UI; the validation report lists the exact missing field.
- **Tag pushed but workflow didn't run**: confirm the tag matches `v*` (not `V*` or just `0.1.0`). `git push origin v0.1.0` is the right form.
