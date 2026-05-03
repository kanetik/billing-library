# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Breaking

- **`BillingActions.handlePurchase` now returns a sealed
  `HandlePurchaseResult`** instead of `Unit`. The previous behavior (throws
  `BillingException` on failure) was too easy to defeat with
  `runCatching { handlePurchase(...) }; grantPremium()` — the entitlement
  grant ran whether or not the acknowledge landed, and Play auto-refunded
  the unacknowledged purchase ~3 days later. Annotated with `@CheckResult`
  so Android lint warns on ignored return values (Kotlin doesn't enforce
  return-value usage at the language level).

  ```kotlin
  // Before:
  try { billing.handlePurchase(purchase, consume = false); grant() }
  catch (e: BillingException) { showError() }

  // After:
  when (val r = billing.handlePurchase(purchase, consume = false)) {
      HandlePurchaseResult.Success -> grant()
      HandlePurchaseResult.NotPurchased -> {} // pending
      is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
  }
  ```

  Lower-level `consumePurchase` and `acknowledgePurchase` are unchanged —
  they still throw `BillingException` directly. Only the high-level
  `handlePurchase` helper gets the typed-result treatment.

- **`BillingPurchaseUpdatesOwner.observePurchaseUpdates()` return type
  changed** from `SharedFlow<PurchasesUpdate>` to `Flow<PurchasesUpdate>`.
  Most consumers using `.collect { }` are unaffected; consumers using
  `SharedFlow`-specific APIs (`.replayCache`, `.subscriptionCount`, etc.)
  must adapt. The change is forced by the underlying split-channel
  architecture (live events on `replay = 0`, recovery events on
  `replay = 1`); a single `SharedFlow` can't express that.

- **`ProductDetails.toOneTimeFlowParams(...)` and
  `PurchaseFlowCoordinator.launch(...)` gained an `obfuscatedProfileId`
  parameter.**
  - **Kotlin callers**: source-compatible. Positional `(obfuscatedAccountId,
    offerSelector)` calls still resolve correctly (the new param sits at the
    end of the list after `offerSelector` and defaults to null), and named-arg
    callers are unaffected.
  - **Java callers**: source-incompatible. Neither API uses `@JvmOverloads`,
    so Kotlin's default-arg machinery doesn't generate a Java-visible bridge
    for the old signature. Java callsites need to add the new parameter
    explicitly (or pass `null`).
  - **All callers**: binary-incompatible. Existing compiled consumer `.class`
    files calling the old signature need a rebuild to link against the new
    method descriptor.

  Recommended Kotlin call style is named args; Java consumers should pin to
  a library version and rebuild together.

### Added

- **`HandlePurchaseResult` sealed type** (`com.kanetik.billing`) —
  `Success`, `NotPurchased`, `Failure(exception)`. See the breaking-change
  note above.
- **`BillingErrorCategory` enum** (`com.kanetik.billing.exception`) — six
  user-facing buckets (`UserCanceled`, `Network`, `BillingUnavailable`,
  `ProductUnavailable`, `DeveloperError`, `Other`) collapsing the 12
  `BillingException` subtypes so callers can localize from a small
  string-resource map instead of branching on every PBL response code.
- **`BillingException.userFacingCategory`** — convenience property returning
  the matching `BillingErrorCategory` for the exception.
- **`obfuscatedProfileId` parameter** on `ProductDetails.toOneTimeFlowParams(...)`
  and `PurchaseFlowCoordinator.launch(...)` — secondary opaque ID for apps
  with multiple user profiles per install. Default-null preserves existing
  call sites verbatim.
- **Automatic purchase recovery on connect** — on every successful Play Billing
  connection, the library queries owned `INAPP` + `SUBS` purchases in parallel
  and emits any `PURCHASED && !isAcknowledged` matches through
  `observePurchaseUpdates()` as a new `PurchasesUpdate.Recovered` variant.
  Closes the gap that lets Play auto-refund stranded purchases after 3 days
  when an app crash, network failure, or process death interrupts the
  acknowledgement path. Opt-out via
  `BillingRepositoryCreator.create(recoverPurchasesOnConnect = false)` for
  consumers running their own server-side reconciliation.
- **`PurchasesUpdate.Recovered(purchases)` sealed variant** — same payload
  as `Success`, distinct variant so consumer UX can differentiate
  user-initiated purchases (fire confetti) from background recovery (silent).
  Handle code is identical to `Success` — call
  `handlePurchase(purchase, consume = ?)` and grant entitlement.

### Changed

- **`handlePurchase` KDoc** now leads with the failure-handling consequence:
  *"do NOT grant entitlement unless this returns normally — Play will
  auto-refund within 3 days and the user's premium will silently evaporate."*
  Plus the multi-quantity gotcha and the `Recovered`-variant handling parity.
- **`BillingException` class-level KDoc** documents that `.message` is a
  debug-context dump for logs only, and routes UI handling through
  `userFacingCategory`.
- **README** gains a "Purchase recovery" section explaining the new
  `Recovered` variant and the auto-sweep behavior.
- **README** gains "Showing errors to users" and "Handling `handlePurchase`
  failures correctly" subsections under Error handling.
- **README** gains a "Granting entitlement: multi-quantity" section
  reminding consumers to read `purchase.quantity` when granting
  consumable entitlement (Play supports multi-quantity purchases; the
  field defaults to 1, so single-unit code keeps working but
  silently under-grants on multi-quantity).
- **`PurchasesUpdate` KDoc** documents the new variant and the
  multi-quantity grant rule at the class level.
- **Sample** updated to handle both `Success` and `Recovered` through one
  shared dispatch.

### Migration notes

`PurchasesUpdate` is a sealed class; adding `Recovered` produces a Kotlin
exhaustiveness warning at any `when (update) { ... }` site that doesn't
have an `else`. Add an arm:

```kotlin
is PurchasesUpdate.Recovered -> update.purchases.forEach { handle(it) }
```

The handle/grant code is the same as your `Success` arm.

## [0.1.0] - 2026-04-30

### Added

- **Google Play Billing Library 8.x baseline** — `enableAutoServiceReconnection`,
  sub-response code support, `enableOneTimeProducts`.
- **Coroutine-first public API** —
  `BillingRepositoryCreator.create(context, logger?, billingClientFactory?, scope?, ioDispatcher?, uiDispatcher?)`
  returns a `BillingRepository` composed of `BillingActions` + `BillingConnector`
  + `BillingPurchaseUpdatesOwner`.
- **Typed exception hierarchy** — `BillingException` (sealed) with 12 subtypes,
  each carrying a `RetryType` hint (`SAFE`, `UNSAFE`, `NEVER`) so consumers can
  branch retry-vs-surface decisions without inspecting response codes.
- **Lifecycle-aware connection sharing** — `BillingConnectionLifecycleManager`
  observes `onStart`/`onStop`/`onDestroy`. Connection is shared via
  `SharingStarted.WhileSubscribed(60_000)` to avoid reconnection churn while
  letting the connection eventually release.
- **Exponential backoff** for retryable failures, capped at three attempts.
  `launchFlow` opts out (single attempt) so UI-initiated purchases never
  silently retry behind the user.
- **`PurchasesUpdate` sealed type** — `Success`, `Pending`, `Canceled`,
  `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse(code)`. `Pending`
  carries a cardinal-rule KDoc warning against entitlement grants on
  pending purchases.
- **`handlePurchase(purchase, consume: Boolean)` helper** — bakes in the
  `purchaseState == PURCHASED` no-op guard plus the consume-vs-acknowledge
  dispatch.
- **Convenience overloads** — `consumePurchase(Purchase)` and
  `acknowledgePurchase(Purchase)` (the latter with an `isAcknowledged`
  short-circuit per Google's explicit guidance).
- **`showInAppMessages(activity, params)`** — exposes PBL 8's transactional
  messaging UI ("fix your payment method") with a sealed
  `BillingInAppMessageResult` (`NoActionNeeded` | `SubscriptionStatusUpdated`)
  so PBL's `InAppMessageResult` shape doesn't pin our ABI.
- **Pluggable `BillingLogger` interface** with `BillingLogger.Noop` (silent
  default) and `BillingLogger.Android` (logcat opt-in). Threaded through
  ~17 internal log sites; consumer wires Crashlytics or similar via a small
  custom adapter.
- **Opt-in extensions** in `com.kanetik.billing.ext`:
  - `validatePurchaseActivity(activity)` — RESUMED gate (not just STARTED),
    handles finishing/destroyed and non-LifecycleOwner activities.
  - `ProductDetails.toOneTimeFlowParams(obfuscatedAccountId?, offerSelector?)`
    — multi-offer one-time products supported via the lambda.
  - `PurchaseFlowCoordinator` — in-flight guard with `compareAndSet` watchdog
    + correlation-id logging; sealed `PurchaseFlowResult` with `data object`
    variants (`Success`, `InvalidActivityState`, `AlreadyInProgress`,
    `BillingUnavailable`, `Error(cause)`).
- **Signature verification helper** —
  `com.kanetik.billing.security.PurchaseVerifier` does RSA signature
  verification with a pluggable `signatureAlgorithm` (defaults to
  PBL-current `SHA1withRSA`).
- **Public test seam** — `BillingClientFactory` interface +
  `DefaultBillingClientFactory` impl; consumers can swap the underlying
  `BillingClient` builder without forking the library.
- **58 unit tests** across 8 files covering exception mapping, logger
  routing, activity validation, purchase dispatching, listener partition
  logic, purchase-flow coordinator state machine, and lifecycle manager
  job discipline. Pure-JVM (no Robolectric); `:billing-testing` artifact
  with Robolectric coverage planned for v0.2.0.
- **Maven Central publishing infrastructure** — vanniktech maven-publish
  plugin wiring, Apache-2.0 + scm + developer POM metadata, GitHub Actions
  CI on PR/main and tag-driven publish to Sonatype Central Portal staging.
- **Documentation** — `/docs/MANUAL_SETUP.md`, `/docs/BUILD_HISTORY.md`,
  `/docs/ROADMAP.md`.

### Attribution

This project is a substantial rewrite of
[`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy)
(Apache-2.0, last upstream update December 2022). The core architecture
(connection factory, retry loop, sealed result types) is rewritten on top
of Play Billing Library 8.x with PBL-8-specific features, typed exceptions,
lifecycle awareness, pluggable logging, and ext helpers added.
