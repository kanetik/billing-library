# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.3] - 2026-05-25

### Breaking

- **`EntitlementCache` is now generic on `K : Any`.** Previously the cache
  modeled a single binary entitlement — fine for "premium unlock" apps,
  wrong for any app with multiple non-consumable SKUs (a game with several
  unlockable upgrades, an app with a Pro toolkit *and* an expansion pack,
  etc.). The new shape is `EntitlementCache<K>` where `K` is whatever your
  app uses to discriminate entitlements (`Unit` for the single-flag case,
  `String` for product-ID-keyed maps, an `enum class` or sealed class for
  named entitlements). The state map is `StateFlow<Map<K, EntitlementState>>`;
  per-key access via the new `stateFor(key: K): Flow<EntitlementState>`
  convenience.

  - `productPredicate: (Purchase) -> Boolean` renamed to
    `productKeySelector: (Purchase) -> K?` — returning `null` means
    "this purchase doesn't grant any tracked entitlement" (use this for
    consumables and any other ignored SKUs).
  - `EntitlementStorage` is now `EntitlementStorage<K>`:
    `readAll(): Map<K, EntitlementSnapshot>` + `write(key: K, snapshot: EntitlementSnapshot)`.
    Single-snapshot implementations from 0.1.2 need to be ported to the
    per-key map shape.
  - `SignedEntitlementStorage` is now `SignedEntitlementStorage<K>` with
    a required `keyToStorageId: (K) -> String` lambda — the serialized key
    is used as the lookup ID in `SignatureStore` and is also included in
    the v2 canonical bytes (cross-key signature swaps now fail verification).
  - `SignatureStore` is now per-entitlement-key:
    `readSignature(entitlementKey: String) / writeSignature(entitlementKey, signature) / clearSignature(entitlementKey)`.
    The bundled `SharedPreferencesSignatureStore` stores blobs under
    `"signature:" + entitlementKey` — distinct from the v0.1.2 `"signature"`
    key, so the first read after upgrade fires `TamperEvent.MissingSignature`
    once per entitlement and the recovery sweep re-confirms truth.
  - `migrateUnsignedSnapshot` now takes `entitlementKey: String` +
    `entitlementCacheKey: K` parameters so consumers running their own
    pre-upgrade migration can target each key.
  - `SnapshotCanonicalBytes` bumped to v2 (key included in canonical
    encoding). v1 sigs continue to verify on read (the v1 encoder still
    exists and ignores the key), so v0.1.2 single-entitlement consumers
    upgrading with `K = Unit` and an empty `keyToStorageId` see no spurious
    `InvalidSignature` events.
  - `onTamperDetected` callback signature changed from
    `(TamperEvent) -> Unit` to `(entitlementKey: String, TamperEvent) -> Unit`.

  Migration for single-entitlement consumers: pick `K = Unit` (or a `data object`
  key), have `productKeySelector` return that key for the matching product
  and `null` otherwise, and port your `EntitlementStorage` to read/write a
  one-entry map. The single-line `stateFor(yourKey)` returns the same
  per-key `Flow<EntitlementState>` you'd previously bound to the UI.

### Added

- **`OneTimePurchaseOfferDetails.isPreorder` extension property.** Sugar
  over PBL 8.1+'s `getPreorderDetails()` null-check, for use in
  `toOneTimeFlowParams`'s `offerSelector`. New
  [Pre-order / multi-offer products](https://kanetik.github.io/billing-library/guides/multi-offer-products/)
  guide entry covers the pre-order pending-fulfillment / cancellation
  caveats.
- **Consumables ledger guide.** New
  [docs page](https://kanetik.github.io/billing-library/guides/consumables/)
  documenting the wallet-on-the-side pattern for consumable SKUs (coins,
  fuel, gems) — explicitly distinct from `EntitlementCache`, with a
  worked example showing how a multi-quantity consume + grant works
  alongside `productKeySelector` returning `null` for consumables.
- **Docs sweep: example framing.** KDoc and guide examples updated from
  "premium unlock" framing to use-case-agnostic naming (one non-consumable
  upgrade + one consumable currency pack, multi-entitlement games, etc.).
  No API impact; aimed at first-time readers who weren't building a
  premium-unlock app and bounced off the previous framing.

### Notes

- The original v0.1.3 scope included a `setPurchaseQuantity` knob on
  `toOneTimeFlowParams` — dropped because PBL 9 doesn't expose
  `setPurchaseQuantity` on `BillingFlowParams.ProductDetailsParams.Builder`.
  In PBL 9, multi-quantity is Play-Console-flag + Play-dialog-driven only;
  the client reads `purchase.quantity` on the way back. The
  [Multi-quantity purchases](https://kanetik.github.io/billing-library/guides/multi-quantity/)
  guide was updated to make this explicit.

## [0.1.2] - 2026-05-24

### Added

- **`SignedEntitlementStorage` decorator + HMAC key-provider helpers for
  tamper-resistant on-device persistence.** Wraps any existing
  `EntitlementStorage` implementation, signs the snapshot on every write
  (HMAC-SHA256 over a versioned canonical encoding), and verifies the
  signature on read. Tampered or unsigned snapshots are dropped — the cache
  reads them as cold-start and the next `OwnedPurchases.Live` or
  `OwnedPurchases.Recovered` re-confirms truth (often within the same
  launch, once the billing connection establishes). Closes the gap that previously kept freemium-with-real-value apps
  from adopting `EntitlementCache`: the library now ships first-party
  signing instead of telling consumers to roll their own. New public types,
  all under `com.kanetik.billing.entitlement.signed`:

  - `SignedEntitlementStorage` — the decorator. Includes a `TamperEvent`
    callback (`MissingSignature` / `InvalidSignature` / `UnsupportedVersion`)
    so consumers can route the common one-time post-upgrade case differently
    from the strong tamper indicator in their telemetry. Includes a static
    `migrateUnsignedSnapshot` helper for adopters who want to avoid the
    one-time cold-start by trusting an existing snapshot once.
  - `HmacKeyProvider` — interface; `sign(data) / verify(data, sig)` shape
    so non-extractable Keystore keys are first-class.
  - `KeystoreBackedKeyProvider` — Android Keystore-backed HMAC-SHA256, the
    recommended default. Hardware-backed where available.
  - `ServerSeededKeyProvider` — per-install seed fetched from a backend on
    first use, cached locally. The default `SharedPreferencesSeedCache` is
    plaintext; consumers needing real tamper resistance should prefer
    `KeystoreBackedKeyProvider` (caveat documented on both types).
  - `SignatureStore` / `SeedCache` interfaces with
    `SharedPreferencesSignatureStore` / `SharedPreferencesSeedCache`
    defaults. Consumers can swap either with their own backend (DataStore,
    Room, encrypted prefs).

  The signature wire format is `4-byte version || 32-byte HMAC-SHA256` over
  a deterministic canonical encoding of `(version, snapshot)`. Versioned
  signing means future field additions to `EntitlementSnapshot` won't
  invalidate existing signatures — signed at v1 keeps verifying at v2 as
  long as the new field is nullable / has a documented v1-default. (#16)

### Breaking

- **`HandlePurchaseResult` sealed class gained a new `NotOwned` subtype.**
  Same exhaustive-`when` story as the other sealed-type additions in 0.1.x:
  consumers branching exhaustively on `HandlePurchaseResult` without an
  `else` arm need to add a branch for `HandlePurchaseResult.NotOwned`.
  Previously, an `ITEM_NOT_OWNED` thrown by the underlying
  `acknowledgePurchase` / `consumePurchase` call was caught by
  `handlePurchase` and surfaced as
  `Failure(BillingException.ItemNotOwnedException)`, which conflated two
  semantically-distinct cases under one `Failure` bucket: transient
  ack-call failures (where ownership is unchanged and the next recovery
  sweep will retry) versus ownership-mismatch (where retrying the ack
  against a non-owned purchase keeps returning `ITEM_NOT_OWNED` forever).
  The new `NotOwned` variant carves the latter out so consumers can
  defer to their grace / revoke logic instead of mis-treating it as
  a retry case.

  ```kotlin
  // Before:
  when (val r = billing.handlePurchase(purchase, consume = false)) {
      HandlePurchaseResult.Success,
      HandlePurchaseResult.AlreadyAcknowledged -> grant()
      HandlePurchaseResult.NotPurchased -> {} // pending
      is HandlePurchaseResult.Failure -> {
          if (r.exception is BillingException.ItemNotOwnedException) {
              // queryPurchases was stale; don't grant; defer to grace-or-revoke
          } else {
              // Transient ack failure; recovery sweep retries on next connect
              showError(r.exception.userFacingCategory)
          }
      }
  }

  // After:
  when (val r = billing.handlePurchase(purchase, consume = false)) {
      HandlePurchaseResult.Success,
      HandlePurchaseResult.AlreadyAcknowledged -> grant()
      HandlePurchaseResult.NotPurchased -> {} // pending
      HandlePurchaseResult.NotOwned -> {
          // Ownership disagrees with the input — Play says this purchase
          // isn't owned anymore. Don't grant; defer to grace/revoke logic
          // and consider re-querying owned purchases.
      }
      is HandlePurchaseResult.Failure -> {
          // Now unambiguously a transient or terminal ack-call failure.
          showError(r.exception.userFacingCategory)
      }
  }
  ```

  `Failure(BillingException.ItemNotOwnedException)` no longer occurs from
  `handlePurchase` — the exception subclass still exists (lower-level
  `acknowledgePurchase` / `consumePurchase` continue to throw it directly),
  but the high-level helper now maps it to the typed `NotOwned` variant
  before returning.

### Fixed

- **`DefaultBillingRepository.launchFlow` now propagates
  `CancellationException` correctly.** The prior broad `catch (e: Exception)`
  block would wrap a `CancellationException` into a `BillingException`,
  silently breaking structured cancellation when the surrounding scope was
  torn down. Added a dedicated `catch (ce: CancellationException) { throw ce }`
  arm before the general wrapping logic so `launchFlow` upholds the same CE
  contract as every other suspend member. (#15)
- **`OwnedPurchases.Live` events with `purchases.isEmpty()` are no longer
  forwarded to consumers.** PBL occasionally fires
  `PurchasesUpdatedListener.onPurchasesUpdated` with no purchases at all
  (settled and pending both empty); the listener used to forward an
  `OwnedPurchases.Live(emptyList())` event in that case. Consumers writing
  `event.purchases` into an entitlement cache silently wiped state on every
  empty callback. The empty event carries no actionable signal — Pending
  purchases route through `FlowOutcome.Pending` separately — so it's now
  dropped at the source in `FlowPurchasesUpdatedListener`. Symmetric with
  the existing empty-`Recovered` filter in `BillingClientStorage`.

  **Behavioral change for consumers that explicitly handle empty Live**
  (telemetry, debug logging, etc.): the empty-purchases sub-branch
  inside an `is OwnedPurchases.Live ->` arm is now unreachable. No-ops
  keyed off `event.purchases.isEmpty()` should be removed; debug counters
  that incremented on every empty callback won't fire. Consumers that
  already merge (rather than replace) on `Live` need no change. (#13)

### Changed

- **(beta)** Bumped Play Billing Library `8.3.0` → `9.0.0`
  ([release notes](https://developer.android.com/google/play/billing/release-notes)).
  Play now returns `BILLING_UNAVAILABLE` (instead of `ERROR`) when the Play
  Store app is blocked by the system (e.g., OEM-customized kids mode). The
  wrapper propagates this correctly through the existing exception hierarchy
  (`BillingUnavailableException` instead of `FatalErrorException`) — no
  wrapper code changes were required, but the exception type seen by consumers
  in this scenario changes.

### Public API changes (BREAKING — bump major if any)

None — wrapper public API is unchanged by this bump.

### Risky items flagged for follow-up

- **Behavior change (error code): Play Store blocked → `BILLING_UNAVAILABLE`
  instead of `ERROR` ([release notes](https://developer.android.com/google/play/billing/release-notes#9.0.0)).**
  Consumers who previously caught `BillingException.FatalErrorException` to
  handle the blocked-Play-Store case will no longer match; they should add a
  `BillingException.BillingUnavailableException` branch (which is the
  semantically correct exception anyway — "billing is unavailable" is more
  accurate than "fatal error" for a policy block). Retry logic also differs:
  `FatalErrorException` uses `EXPONENTIAL_RETRY`; `BillingUnavailableException`
  uses `NONE`. Requires `androidx.core` ≥ 1.9.

- **`"Play Store is blocked"` debug message propagates automatically.**
  PBL 9 sets a specific debug message on the `BillingResult` for the
  system-blocked case. No wrapper API change was needed — the message
  reaches consumers through paths the library already exposes:
  - **Logs:** `BillingUnavailableException.message` includes it (built by
    `BillingLoggingUtils.createDetailedBillingContext`), so Crashlytics /
    Timber output for that exception shows `Debug: 'Play Store is blocked'`.
  - **Programmatic differentiation:** consumers that need to distinguish
    a system-block from other `BILLING_UNAVAILABLE` causes (Play Services
    missing, ineligible account, non-Play distribution) can read
    `exception.result?.debugMessage` directly — `result` is the public
    `BillingResult` field on every `BillingException` subtype. Per the
    `BillingException` class KDoc, `result.debugMessage` is the documented
    path for programmatic branching; `.message` is for logs only.

- **`BillingActions` class-level KDoc** gains a "Wrapping suspend members for
  resilience" note explaining that suspend members propagate structured
  cancellation (parent-scope `CancellationException` is rethrown; an internal
  `withTimeout`'s `TimeoutCancellationException` is intentionally converted
  to a `BillingException` because it represents a billing-layer failure),
  and that `runCatching { ... }` catches all `Throwable` (including
  `CancellationException`) and silently re-introduces the swallow-CE footgun
  at the consumer layer. The note recommends explicit `try/catch` with a
  `CancellationException` rethrow rather than `runCatching`. (#15)
- **`BillingPurchaseUpdatesOwner.observePurchaseUpdates` KDoc** gains an
  equivalent resilience note — long-lived collectors are the most common
  cancellation-swallowing site, so the warning is repeated there for
  visibility. (#15)
- **`BillingActions.launchFlow` KDoc** expanded with the dual throw-vs-event
  contract: synchronous PBL errors (invalid activity, `NullPointerException`
  from `client.launchBillingFlow`, other `Exception`s, and any non-`OK`
  `BillingResponseCode` returned synchronously — including
  `ITEM_ALREADY_OWNED` when PBL knows without showing UI) surface as thrown
  `BillingException` subtypes, while UI-mediated outcomes arrive on
  `observePurchaseUpdates` as `FlowOutcome` variants. Calls out that
  `ITEM_ALREADY_OWNED` is dual-path and must be handled in both the
  `try/catch` around `launchFlow` *and* the `FlowOutcome.ItemAlreadyOwned`
  branch in the collector. Also clarifies that the returned coroutine
  completes once `BillingClient.launchBillingFlow` has returned (i.e., the
  request was submitted to Play) — PBL exposes no "UI rendered" signal, so
  completion is not a visibility guarantee. (#17)

## [0.1.1] - 2026-05-03

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
  changed** from `SharedFlow<PurchasesUpdate>` to `Flow<PurchaseEvent>`.
  Two changes folded together: the wrapper type became `Flow` (forced by
  the underlying split-channel architecture — live events on `replay = 0`,
  recovery events on `replay = 1`; a single `SharedFlow` can't express
  that), and the element type became `PurchaseEvent` (see the
  `PurchasesUpdate` → `PurchaseEvent` split below). Most consumers using
  `.collect { }` are unaffected at the call site beyond the rename;
  consumers using `SharedFlow`-specific APIs (`.replayCache`,
  `.subscriptionCount`, etc.) must adapt.

- **`ProductDetails.toOneTimeFlowParams(...)` and
  `PurchaseFlowCoordinator.launch(...)` gained an `obfuscatedProfileId`
  parameter.** The new param sits between `obfuscatedAccountId` and
  `offerSelector` so trailing-lambda calls (`product.toOneTimeFlowParams { ... }`
  or `product.toOneTimeFlowParams("acct") { ... }`) keep compiling.
  - **Kotlin trailing-lambda callers**: source-compatible.
  - **Kotlin positional 2-arg callers** (`product.toOneTimeFlowParams(accountId,
    customSelector)`): source-incompatible. The second positional slot is now
    `obfuscatedProfileId: String?`, producing a type-mismatch compile error.
    Migration: switch to named args (`obfuscatedAccountId = ...,
    offerSelector = ...`).
  - **Java callers**: source-incompatible. Neither API uses `@JvmOverloads`,
    so Kotlin's default-arg machinery doesn't generate a Java-visible bridge
    for the old signature. Java callsites need to add the new parameter
    explicitly (or pass `null`).
  - **All callers**: binary-incompatible. Existing compiled consumer `.class`
    files calling the old signature need a rebuild to link against the new
    method descriptor.

  Recommended Kotlin call style is named args; Java consumers should pin to
  a library version and rebuild together.

- **`BillingRepositoryCreator.create(...)` gained a
  `recoverPurchasesOnConnect: Boolean = true` parameter.** Same compat
  story as `obfuscatedProfileId`:
  - **Kotlin callers using named args or relying on the default**:
    source-compatible.
  - **Java callers**: source-incompatible. No `@JvmOverloads` bridge; Java
    callsites need to pass the new arg explicitly.
  - **All callers**: binary-incompatible. Compiled consumer `.class` files
    calling the old signature need a rebuild.

  Set `false` if you run your own server-side reconciliation queue; default
  `true` enables auto-recovery of unacknowledged purchases on each connect.

- **`PurchaseFlowCoordinator(...)` constructor gained a
  `uiDispatcher: CoroutineDispatcher = Dispatchers.Main` parameter.** Same
  compat story:
  - **Kotlin callers using named args or relying on the default**:
    source-compatible.
  - **Kotlin callers using all-positional construction** (rare):
    source-compatible (the new param sits at the end after `watchdogTimeoutMs`).
  - **Java callers**: source-incompatible. No `@JvmOverloads` bridge.
  - **All callers**: binary-incompatible. Recompile required.

  The dispatcher is used for the explicit `withContext` wrap around
  `BillingRepository.launchFlow` (defensive against custom `BillingRepository`
  implementations that don't dispatch internally; tunable in tests).

- **`BillingException` sealed class gained a new `WrappedException` subtype.**
  Adding a sealed-class subtype is a Kotlin source break for any
  consumer doing exhaustive `when (e: BillingException) { ... }` without
  an `else` branch. Migration: add a branch for `BillingException.WrappedException`
  (or fall back to an `else` if you don't care to distinguish it from the
  other "something unexpected happened" subtypes — `WrappedException` maps
  to `BillingErrorCategory.Other` for UI purposes). See the Added section
  below for what `WrappedException` represents.

- **`PurchasesUpdate` sealed class replaced with `PurchaseEvent` marker
  interface, split into `OwnedPurchases` and `FlowOutcome`.** Lumping
  owned-state events (`Success`, `Recovered`) and flow-attempt outcomes
  (`Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`,
  `UnknownResponse`) under a single sealed class with an
  `abstract val purchases` field made it natural for consumers to write
  `update.purchases` into their entitlement cache from any branch — silently
  corrupting state when the event was actually a `Canceled` (empty list) or
  `Pending` (purchases that haven't completed yet). The new shape eliminates
  that specific footgun at compile time (the marker interface has no
  `purchases` property; reading it requires narrowing to a root):

  ```kotlin
  sealed interface PurchaseEvent  // no purchases property — must narrow

  sealed class OwnedPurchases : PurchaseEvent {
      abstract val purchases: List<Purchase>
      data class Live(...) : OwnedPurchases()       // renamed from Success
      data class Recovered(...) : OwnedPurchases()
  }

  sealed class FlowOutcome : PurchaseEvent {
      abstract val purchases: List<Purchase>
      data class Pending(...) : FlowOutcome()
      data class Canceled(...) : FlowOutcome()
      data class ItemAlreadyOwned(...) : FlowOutcome()
      data class ItemUnavailable(...) : FlowOutcome()
      data class UnknownResponse(val code: Int, ...) : FlowOutcome()
  }
  ```

  Because `PurchaseEvent` itself has no `purchases` property, reading
  purchases requires narrowing to `OwnedPurchases` or `FlowOutcome` first.
  `Success` is renamed to `Live`: `Success` was misleading because both
  `Live` and `Recovered` convey owned state.

  **Note on cache writes:** `OwnedPurchases` events are incremental updates,
  not authoritative owned-state snapshots. `Live` forwards whatever PBL
  delivers on `OK` (including empty callbacks and `UNSPECIFIED_STATE`
  entries); `Recovered` carries only the `PURCHASED && !isAcknowledged`
  subset from the auto-sweep. Merge into your entitlement state on
  `handlePurchase` Success rather than replacing your cache from
  `event.purchases`. For managed entitlement state with grace policy, use
  `EntitlementCache` (issue #3).

  Migration:

  ```kotlin
  // Before:
  when (update) {
      is PurchasesUpdate.Success -> update.purchases.forEach { handleAndGrant(it) }
      is PurchasesUpdate.Recovered -> update.purchases.forEach { handleAndGrant(it) }
      is PurchasesUpdate.Pending -> showPendingNotice()
      is PurchasesUpdate.Canceled -> {}
      is PurchasesUpdate.ItemAlreadyOwned -> restoreEntitlement()
      is PurchasesUpdate.ItemUnavailable -> showSoldOut()
      is PurchasesUpdate.UnknownResponse -> reportFailure(update.code)
  }

  // After (same exhaustive shape):
  when (event) {
      is OwnedPurchases.Live -> event.purchases.forEach { handleAndGrant(it) }
      is OwnedPurchases.Recovered -> event.purchases.forEach { handleAndGrant(it) }
      is FlowOutcome.Pending -> showPendingNotice()
      is FlowOutcome.Canceled -> {}
      is FlowOutcome.ItemAlreadyOwned -> restoreEntitlement()
      is FlowOutcome.ItemUnavailable -> showSoldOut()
      is FlowOutcome.UnknownResponse -> reportFailure(event.code)
  }

  // Or, when the owned-state arms collapse to one handler:
  when (event) {
      is OwnedPurchases -> event.purchases.forEach { handleAndGrant(it) }
      is FlowOutcome -> { /* sub-when on Pending / Canceled / etc. */ }
  }
  ```

  The `observePurchaseUpdates()` method name is unchanged; only its return
  type changes from `Flow<PurchasesUpdate>` to `Flow<PurchaseEvent>`. The
  `Recovered` channel still carries `replay = 1` and still requires the
  `purchaseToken`-based dedupe pattern documented in the README "Purchase
  recovery" section.

- **`HandlePurchaseResult` sealed class gained a new `AlreadyAcknowledged`
  subtype.** Same story as the other sealed-type additions in this release:
  exhaustive `when (r: HandlePurchaseResult) { ... }` without an `else`
  branch becomes a Kotlin source break. Migration: add a branch for
  `HandlePurchaseResult.AlreadyAcknowledged` — typically map it to the
  same grant call as `Success` (it's entitlement-equivalent), and
  optionally log it distinctly so telemetry can separate "we just acked"
  from "it was already done". See the README
  "Handling `handlePurchase` failures correctly" section.

- **`PurchaseEvent` gained a third top-level variant: `PurchaseRevoked`.**
  Same exhaustiveness story as the two-tier split above — `when (event)`
  sites need a `is PurchaseRevoked -> ...` arm. `PurchaseRevoked` is **not**
  nested under `OwnedPurchases` or `FlowOutcome`: it's neither owned-state
  nor a flow-attempt outcome but a third category (external signal), and it
  carries no `Purchase` objects (the source is a server-side notification
  carrying a `purchaseToken`, not a re-issued PBL callback). Branch on
  `event.purchaseToken` and `event.reason` (a `RevocationReason` enum) and
  revoke the matching entitlement. The library does not emit `PurchaseRevoked`
  itself; it surfaces events the consumer pushes via the new
  `BillingRepository.emitExternalRevocation` API (see Added below). See the
  README "Server-driven revocation" section for the full pattern.

- **`BillingRepository` interface gained a `suspend emitExternalRevocation(purchaseToken, reason)`
  method.** Source break for any consumer implementing `BillingRepository`
  directly (rare — most consumers use `BillingRepositoryCreator.create(...)`,
  which returns the library-provided implementation). Custom implementations
  must add the new method; the simplest pass-through is to delegate to a
  `MutableSharedFlow<PurchaseRevoked>` that feeds into the `PurchaseEvent`
  stream backing `observePurchaseUpdates()`.

### Added

- **`HandlePurchaseResult` sealed type** (`com.kanetik.billing`) —
  `Success`, `AlreadyAcknowledged`, `NotPurchased`, `Failure(exception)`.
  See the breaking-change note above.
- **`HandlePurchaseResult.AlreadyAcknowledged` variant** (`data object`) —
  returned by `handlePurchase(purchase, consume = false)` when
  `purchase.isAcknowledged` is already `true`. The library short-circuits
  before reaching out to PBL (no acknowledge call is made), closing the
  recovery hole where calling acknowledge on an already-acked purchase
  surfaced `Failure(DeveloperErrorException)` and made "already acked"
  indistinguishable from a real ack failure. Treat as entitlement-
  equivalent to `Success`; log distinctly if telemetry needs to
  separate the two. The `consume = true` path does not produce this
  variant — consumables are consumed, not acknowledged, and Play
  doesn't expose an `isConsumed` field on `Purchase` for a parallel
  check.
- **`BillingException.WrappedException(cause)` sealed subtype** — synthesized
  by `handlePurchase` when a custom `BillingActions` implementation throws
  something other than `BillingException` (an `IllegalStateException` from
  a fake, an `AssertionError` from a test double, etc.). Distinct from
  `UnknownException` (which is reserved for undocumented PBL response
  codes); `result` is `null` and the original throwable is on
  `originalCause` / `Exception.cause`. Brings the total `BillingException`
  subtype count to 13.
- **`BillingErrorCategory` enum** (`com.kanetik.billing.exception`) — seven
  user-facing buckets (`UserCanceled`, `Network`, `BillingUnavailable`,
  `ProductUnavailable`, `AlreadyOwned`, `DeveloperError`, `Other`)
  collapsing the 13 `BillingException` subtypes so callers can localize
  from a small string-resource map instead of branching on every PBL
  response code. (`AlreadyOwned` covers `ItemAlreadyOwnedException` and
  `ItemNotOwnedException` — both ownership-mismatch cases that warrant
  silent restore rather than a generic error UI.)
- **`BillingException.userFacingCategory`** — convenience property returning
  the matching `BillingErrorCategory` for the exception.
- **`obfuscatedProfileId` parameter** on `ProductDetails.toOneTimeFlowParams(...)`
  and `PurchaseFlowCoordinator.launch(...)` — secondary opaque ID for apps
  with multiple user profiles per install. See the Breaking section above
  for the full Kotlin/Java/binary compat story.
- **Automatic purchase recovery on connect** — on every successful Play Billing
  connection, the library queries owned `INAPP` + `SUBS` purchases in parallel
  and emits any `PURCHASED && !isAcknowledged` matches through
  `observePurchaseUpdates()` as an `OwnedPurchases.Recovered` event.
  Closes the gap that lets Play auto-refund stranded purchases after 3 days
  when an app crash, network failure, or process death interrupts the
  acknowledgement path. Opt-out via
  `BillingRepositoryCreator.create(recoverPurchasesOnConnect = false)` for
  consumers running their own server-side reconciliation.
- **`OwnedPurchases.Recovered(purchases)` sealed variant** — same payload
  as `OwnedPurchases.Live`, distinct variant so consumer UX can differentiate
  user-initiated purchases (fire confetti) from background recovery (silent).
  Handle code is identical to `Live` — call
  `handlePurchase(purchase, consume = ?)` and grant entitlement.
- **`FlowOutcome.Failure(exception, purchases)` sealed variant** —
  carries the typed `BillingException` Play Billing surfaced for failure
  response codes (`NETWORK_ERROR`, `BILLING_UNAVAILABLE`,
  `SERVICE_UNAVAILABLE`, `SERVICE_DISCONNECTED`, `FEATURE_NOT_SUPPORTED`,
  `DEVELOPER_ERROR`, `ERROR`, `ITEM_NOT_OWNED`). Consumers that previously
  branched these as `UnknownResponse(code)` can switch to branching on
  `exception.userFacingCategory` / `exception.retryType`. `UnknownResponse`
  is now reserved for response codes PBL doesn't document.
- **`com.kanetik.billing.entitlement` package** (opt-in) — centralizes the
  `(isEntitled, lastConfirmedTs, source)` state machine that every consumer
  was reinventing on top of `observePurchaseUpdates()`. Public types:
  `EntitlementCache`, `EntitlementState` (`Granted` / `InGrace` / `Revoked`),
  `GracePolicy`, `GraceReason` (`BillingUnavailable` / `TransientFailure`),
  `EntitlementSnapshot`, `EntitlementStorage`. Exposes a
  `StateFlow<EntitlementState>` with grace-window logic anchored to the
  last confirmed observation timestamp (so repeated `FlowOutcome.Failure`
  emissions don't extend grace indefinitely), consumer-implemented
  persistence (the library does not pick a persistence layer), and
  grace-expiry re-evaluation on every emission so an extended outage
  correctly transitions `InGrace → Revoked` without external triggers.
  Revocation flows through `PurchaseRevoked` (matched against the cached
  snapshot's `purchaseToken`) — `OwnedPurchases.Live` / `Recovered` are
  treated as grant-only signals, since `Recovered` only emits the unacked
  subset and an empty Recovered for an entitled-but-acked user would
  falsely revoke under a "Recovered is authoritative" interpretation. See
  the README "EntitlementCache (opt-in)" section.
- **`PurchaseRevoked(purchaseToken, reason)` top-level `PurchaseEvent`
  variant + `RevocationReason` enum** (`Refunded`, `Chargeback`,
  `SubscriptionExpired`, `Other`) — synthetic revocation event for
  server-driven entitlement reversals (refunds, chargebacks, etc.). Sits
  alongside `OwnedPurchases` and `FlowOutcome` as a third `PurchaseEvent`
  category rather than nested under either, because it's neither owned
  state nor a flow-attempt outcome. Carries no `Purchase` object
  (revocations originate from a server-side notification, not a re-issued
  PBL callback); branch on `purchaseToken` directly. The library does not
  emit `PurchaseRevoked` itself.
- **`BillingRepository.emitExternalRevocation(purchaseToken, reason)`** —
  transport-agnostic emit API. The library does not subscribe to FCM, RTDN,
  Pub/Sub, or any server-side channel; consumers wiring up RTDN→FCM
  ingestion (or polling, or deeplinks) decode the payload and call this
  method. Routed through a dedicated `replay = 16` channel (separate from
  the `OwnedPurchases.Recovered` channel, since the recovery channel is
  typed narrower than `PurchaseEvent`) so up to 16 revocations arriving
  before a subscriber attaches survive — sized for the realistic FCM-burst
  case (multi-product chargebacks, several revocations decoded at process
  start). See the README "Server-driven revocation" section.

### Changed

- **`handlePurchase(purchase, consume = false)` no longer returns
  `Failure(DeveloperErrorException)` for already-acknowledged purchases.**
  The library now short-circuits at the top of `handlePurchase` when
  `!consume && purchase.isAcknowledged` is true and returns the new
  `HandlePurchaseResult.AlreadyAcknowledged` variant — no PBL
  call is made. Consumers can now safely untrack-on-Failure for retry
  on the next recovery sweep without risking a permanent retry loop on
  an already-acked purchase. `Failure` unambiguously means a transient
  or terminal ack failure worth retrying. The `consume = true` path is
  unchanged (consume always runs regardless of `isAcknowledged`, since
  Play doesn't expose an `isConsumed` analog on `Purchase`).
- **`handlePurchase` KDoc** now leads with the failure-handling consequence:
  *"do NOT grant entitlement unless this returns normally — Play will
  auto-refund within 3 days and the user's premium will silently evaporate."*
  Plus the multi-quantity gotcha and the `Recovered`-variant handling parity.
- **`BillingException` class-level KDoc** documents that `.message` is a
  debug-context dump for logs only, and routes UI handling through
  `userFacingCategory`.
- **README** gains a "Purchase recovery" section explaining the
  `OwnedPurchases.Recovered` variant and the auto-sweep behavior.
- **README** gains "Showing errors to users" and "Handling `handlePurchase`
  failures correctly" subsections under Error handling.
- **README** gains a "Granting entitlement: multi-quantity" section
  reminding consumers to read `purchase.quantity` when granting
  consumable entitlement (Play supports multi-quantity purchases; the
  field defaults to 1, so single-unit code keeps working but
  silently under-grants on multi-quantity).
- **README** gains a "two-tier `PurchaseEvent`" callout under Quick start
  spelling out the `OwnedPurchases` vs `FlowOutcome` cache-write rule.
- **`PurchaseEvent` KDoc** describes the two-tier semantic, the cache-write
  rule, and the multi-quantity grant rule at the marker-interface level.
- **Sample** updated to handle both `OwnedPurchases.Live` and
  `OwnedPurchases.Recovered`, plus a single `is FlowOutcome` arm covering
  the attempt-outcome variants.
- **Recovered events now suppress already-acknowledged purchase tokens
  internally; consumer-side dedupe of Recovered is no longer necessary.**
  `BillingClientStorage` tracks tokens passed through
  `acknowledgePurchase` / `consumePurchase` / `handlePurchase` for its
  own instance lifetime (typically the singleton repository, often the
  process). The recovery channel keeps its `replay = 1` cache (reflecting
  the latest sweep's raw result), but `observePurchaseUpdates()` filters
  the cached snapshot against the acked-token set at delivery time (a
  synchronous `map` reads the current set per emission) — so a late
  subscriber that attaches *after* the consumer has already handled the
  recovered purchase receives the cached sweep result re-filtered against
  the current acked set, not the stale pre-ack snapshot. Empty `Recovered`
  (intrinsic or filtered-to-empty) is dropped before delivery. Late
  subscribers no longer need to maintain a `Set<String>` of handled
  tokens to dedupe replay.

### Migration notes

`PurchasesUpdate` is gone — replaced by the `PurchaseEvent` marker
interface, its two sealed roots (`OwnedPurchases`, `FlowOutcome`), and the
top-level `PurchaseRevoked` variant. Existing
`when (update: PurchasesUpdate) { ... }` sites need to switch to the new
types and add the third arm:

```kotlin
when (event) {
    is OwnedPurchases.Live -> event.purchases.forEach { handle(it) }
    is OwnedPurchases.Recovered -> event.purchases.forEach { handle(it) }
    is FlowOutcome.Pending -> showPendingNotice()
    is FlowOutcome.Canceled -> {}
    is FlowOutcome.ItemAlreadyOwned -> restoreEntitlement()
    is FlowOutcome.ItemUnavailable -> showSoldOut()
    is FlowOutcome.UnknownResponse -> reportFailure(event.code)
    is PurchaseRevoked -> revokeEntitlement(event.purchaseToken, event.reason)
}
```

The handle/grant code is the same for `Live` and `Recovered`. **Do not
write `event.purchases` to your entitlement cache from any `FlowOutcome`
branch** — those events describe attempt outcomes, not owned state. See
the README "Two-tier `PurchaseEvent`" callout under Quick start. The
`PurchaseRevoked` arm is new — wire it to whatever revocation flow (clear
the premium flag, kick to a paywall, log for audit, etc.) makes sense for
your app. The library does not emit `PurchaseRevoked` on its own; events
arrive only when the consumer pushes them via
`BillingRepository.emitExternalRevocation`.

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
- **Documentation** — `/docs/_internal/manual-setup.md`, `/docs/design-notes.md`,
  `/docs/roadmap.md`.

### Attribution

This project is a substantial rewrite of
[`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy)
(Apache-2.0, last upstream update December 2022). The core architecture
(connection factory, retry loop, sealed result types) is rewritten on top
of Play Billing Library 8.x with PBL-8-specific features, typed exceptions,
lifecycle awareness, pluggable logging, and ext helpers added.
