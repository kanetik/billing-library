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
3. Stash them in `~/.gradle/gradle.properties` (gitignored, machine-local):
   ```properties
   mavenCentralUsername=<token-username>
   mavenCentralPassword=<token-password>
   ```
4. These also become the GitHub Actions secrets `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` in step 4 below.

---

## 2. GPG key for artifact signing

Maven Central rejects unsigned artifacts. You need a GPG key whose public half is on a public keyserver, and whose private half is available to Gradle.

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

Maven Central's signature verifier checks `keyserver.ubuntu.com` and `keys.openpgp.org`. Push to both:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEYID>
gpg --keyserver keys.openpgp.org --send-keys <LONG_KEYID>
```

`keys.openpgp.org` requires email confirmation — check `billinglibrary@kanetik.com` for a verification link, click it, and your public key becomes searchable.

### 2.4 Make the private half available to Gradle (local)

For local publishing (`./gradlew :billing:publishToMavenCentral`), add to `~/.gradle/gradle.properties`:

```properties
signing.keyId=<LAST_8_CHARS_OF_LONG_KEYID>
signing.password=<gpg-passphrase>
signing.secretKeyRingFile=/c/Users/jkane/.gnupg/secring.gpg
```

Modern GPG (2.1+) doesn't write `secring.gpg` automatically; you need to export it once:

```bash
gpg --export-secret-keys <LONG_KEYID> > ~/.gnupg/secring.gpg
```

(Path on Windows: `C:\Users\jkane\.gnupg\secring.gpg`. The `signing.secretKeyRingFile` path uses Unix-style slashes.)

### 2.5 Make the private half available to GitHub Actions

The `publish.yml` workflow expects an **ASCII-armored** private key as a secret (no file path — secrets are env vars).

```bash
gpg --armor --export-secret-keys <LONG_KEYID> > /tmp/signing-key.asc
```

The contents of `signing-key.asc` (including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----` lines) becomes the `SIGNING_KEY` GitHub Actions secret in step 4 below. Delete `/tmp/signing-key.asc` after copying.

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
| "Public key not found in keyserver" | Re-push to `keyserver.ubuntu.com` AND `keys.openpgp.org`; wait 30 min for propagation. |
| "POM missing `description` / `developers` / `scm`" | Edit `billing/build.gradle.kts` `mavenPublishing { pom { ... } }` block. |
| "Snapshot version on release endpoint" | You forgot `-PVERSION_NAME=0.1.0-...`; the default `0.1.0-SNAPSHOT` only goes to the snapshot repo, which Central Portal doesn't host. |
| "Namespace not verified" | DNS TXT record hasn't propagated, or it's on the wrong record name. Re-check section 1.3. |

You can drop a failed staging deployment in the Portal UI and re-upload after fixing.

---

## 6. Tag-driven publish (steady state)

Once 5.2 succeeds for a beta, the steady-state release workflow is:

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

## 7. Future: switching to auto-release

After v0.1.0 ships and the pipeline is trusted, change `billing/build.gradle.kts`:

```kotlin
publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
```

…or replace the `publishToMavenCentral` task with `publishAndReleaseToMavenCentral` in `.github/workflows/publish.yml`. Either flips the workflow from "upload to staging" to "upload + release in one step" — no manual Portal click needed.

---

## Troubleshooting

- **`./gradlew: Permission denied` on Linux runners**: the gradle-wrapper script lost its executable bit on Windows. Fix once with `git update-index --chmod=+x gradlew && git commit -m "ci: chmod +x gradlew"`. (Already handled in this repo's git index.)
- **GPG key passphrase prompt during local publish**: pass `--quiet` to suppress, or set `signing.password` in `gradle.properties` (already documented in 2.4).
- **Signed-but-rejected for "validation failed: pom"**: open the failed deployment in the Portal UI; the validation report lists the exact missing field.
- **Tag pushed but workflow didn't run**: confirm the tag matches `v*` (not `V*` or just `0.1.0`). `git push origin v0.1.0` is the right form.
