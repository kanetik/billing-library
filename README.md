# Kanetik Billing Library

A coroutine-first wrapper around [Google Play Billing Library 8.x](https://developer.android.com/google/play/billing). Typed errors with retry-type hints, lifecycle-aware connection sharing, exponential backoff, and opt-in helpers for the patterns most apps reimplement themselves.

## Why

- **Less boilerplate** — `connectToBilling()`, `queryProductDetails(...)`, `launchFlow(...)`, `observePurchaseUpdates()` are coroutine-flavored equivalents of PBL's listener/callback APIs. `observePurchaseUpdates()` returns a `Flow<PurchaseEvent>` split into two sealed roots — `OwnedPurchases` (`Live`, `Recovered`) for owned-state updates and `FlowOutcome` (`Pending`, `Canceled`, etc.) for purchase-flow attempt outcomes — so the type system enforces the cache-write rule at branch sites. No `BillingClientStateListener`, no `PurchasesUpdatedListener` wiring at the call site.
- **Typed errors** — every `BillingResponseCode` lands as a `BillingException` subtype carrying a `RetryType` hint. Branch on the type, not on integers.
- **Lifecycle-aware** — `BillingConnectionLifecycleManager` keeps the connection warm while an activity/process is observable and tears it down on destruction, with a 60-second grace window to absorb configuration changes.

## Installation

```kotlin
dependencies {
    implementation("com.kanetik.billing:billing:0.1.1")
}
```

Requires `minSdk = 23` (PBL 8.1's floor — the library pins to PBL 8.3.0). JVM target is 11.

## Quick start (one-time IAP)

```kotlin
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.kanetik.billing.BillingRepositoryCreator
import com.kanetik.billing.FlowOutcome
import com.kanetik.billing.HandlePurchaseResult
import com.kanetik.billing.OwnedPurchases
import com.kanetik.billing.ext.toOneTimeFlowParams
import com.kanetik.billing.lifecycle.BillingConnectionLifecycleManager
import com.kanetik.billing.logging.BillingLogger

class CheckoutActivity : ComponentActivity() {

    private val billing by lazy {
        BillingRepositoryCreator.create(
            context = applicationContext,
            logger = BillingLogger.Android, // or your own adapter; default is Noop (silent)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the connection alive for the lifetime of this activity.
        lifecycle.addObserver(BillingConnectionLifecycleManager(billing))

        // Observe purchase events from the global PurchasesUpdatedListener.
        // `Recovered` replays its most recent snapshot to re-subscribed
        // collectors (configuration change, ViewModel recreation), so dedupe
        // by purchaseToken in the Recovered branch — see "Purchase recovery"
        // below for the full pattern. Persist `handledRecoveredTokens` if the
        // dedupe needs to survive process death.
        val handledRecoveredTokens = MutableStateFlow<Set<String>>(emptySet())
        lifecycleScope.launch {
            billing.observePurchaseUpdates().collect { event ->
                when (event) {
                    is OwnedPurchases.Live -> event.purchases.forEach { handle(it) }
                    is OwnedPurchases.Recovered -> event.purchases.forEach { purchase ->
                        if (purchase.purchaseToken in handledRecoveredTokens.value) return@forEach
                        // Only mark token as handled on Success — Failure leaves it for the
                        // next sweep to retry, NotPurchased waits for the terminal state.
                        if (handle(purchase)) {
                            handledRecoveredTokens.update { it + purchase.purchaseToken }
                        }
                    }
                    is FlowOutcome.Pending -> showPendingNotice() // do NOT grant entitlement yet
                    is FlowOutcome.Canceled -> {}
                    is FlowOutcome.ItemAlreadyOwned -> restoreEntitlement()
                    is FlowOutcome.ItemUnavailable -> showSoldOut()
                    is FlowOutcome.UnknownResponse -> reportFailure(event.code)
                }
            }
        }
    }

    fun onBuyClicked() = lifecycleScope.launch {
        val products = billing.queryProductDetails(
            QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId("premium_lifetime")
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                ))
                .build()
        )
        val product = products.firstOrNull() ?: return@launch
        billing.launchFlow(this@CheckoutActivity, product.toOneTimeFlowParams())
    }

    /** @return true iff acknowledge/consume landed at Play (safe to mark token as handled). */
    private suspend fun handle(purchase: Purchase): Boolean {
        // handlePurchase returns a sealed HandlePurchaseResult — branch on it.
        // See "Handling handlePurchase failures correctly" below for the full pattern.
        return when (val r = billing.handlePurchase(purchase, consume = false)) {
            HandlePurchaseResult.Success -> { grantPremium(); true }
            HandlePurchaseResult.AlreadyAcknowledged -> { grantPremium(); true } // safe — no PBL call needed
            HandlePurchaseResult.NotPurchased -> false // pending — wait for terminal state
            is HandlePurchaseResult.Failure -> {
                showError(r.exception.userFacingCategory)
                false // recovery sweep retries on next clean connect; don't mark as handled
            }
        }
    }
}
```

That's enough for a working one-time-IAP integration. Subscriptions work at the protocol level via raw `QueryPurchasesParams` + `BillingFlowParams`; subscription-specific helpers ship in v0.2.0 (see [`docs/ROADMAP.md`](docs/ROADMAP.md)).

> **⚠️ Two-tier `PurchaseEvent` — read before writing to your cache**
>
> `observePurchaseUpdates()` emits `PurchaseEvent`, a marker interface with
> two sealed roots:
>
> - **`OwnedPurchases`** (`Live`, `Recovered`) — owned-state updates. The
>   user owns these purchases; acknowledge / consume / grant entitlement.
>   These are **incremental updates, not authoritative owned-state
>   snapshots** — `Live` forwards whatever PBL delivers (including empty
>   callbacks and `UNSPECIFIED_STATE` entries), and `Recovered` carries
>   only the unacknowledged subset from the auto-sweep. Merge into your
>   own entitlement state on `handlePurchase` Success rather than
>   replacing your cache from `event.purchases`. For managed entitlement
>   state with grace policy, use `EntitlementCache` (when issue
>   [#3](https://github.com/kanetik/billing-library/issues/3) lands).
> - **`FlowOutcome`** (`Pending`, `Canceled`, `ItemAlreadyOwned`,
>   `ItemUnavailable`, `UnknownResponse`) — purchase-flow attempt outcomes.
>   These describe what *happened* on a single launch attempt. The
>   `purchases` list is typically empty (or, for `Pending`, purchases
>   that haven't completed yet) and **must not** be written to an
>   entitlement cache.
>
> The marker interface deliberately omits the `purchases` property — you
> can't read `event.purchases` without first narrowing to `OwnedPurchases`
> or `FlowOutcome`. The split's job is to eliminate the original bug:
> writing `update.purchases` from a `Canceled` (or other `FlowOutcome`)
> event into your entitlement cache. The split does **not** promise that
> `OwnedPurchases.purchases` is an authoritative owned-state snapshot —
> see each variant's KDoc for the actual shape.

## Purchase recovery

Play auto-refunds purchases that aren't acknowledged within 3 days. App crashes, network failures, or process death mid-acknowledge can strand a paid purchase — without recovery, the user pays, gets refunded, and never sees the entitlement.

The library handles this for you. On every fresh Play Billing connection (app start, post-disconnect reconnect, foregrounding after the connection released — the underlying connection uses `WhileSubscribed(60s)` so a quick background round-trip *doesn't* reconnect), it queries owned `INAPP` + `SUBS` purchases, filters for `PURCHASED && !isAcknowledged`, and emits any matches as `OwnedPurchases.Recovered`. Your existing `observePurchaseUpdates()` collector picks them up — no startup hook to wire, no scheduling code to write. (Exhaustive `when (event)` collectors do need a branch for `OwnedPurchases.Recovered` distinct from `OwnedPurchases.Live`; see the snippet below.)

This requires that *something* is driving the connection. The standard pattern uses `BillingConnectionLifecycleManager` (see "Lifecycle integration" below), which collects `connectToBilling()` while a `LifecycleOwner` is started and triggers the recovery sweep automatically. Subscribing to `observePurchaseUpdates()` alone does **not** open the connection; pair it with the lifecycle manager (or your own `connectToBilling()` collector) so the sweep can fire. Internally the recovery channel uses `replay = 1` (see "Replay semantics" below), so a subscriber that attaches a moment after the sweep still receives the most recent recovered purchases.

The library handles `Recovered` dedupe for you. `BillingActions.handlePurchase` (and the lower-level `acknowledgePurchase` / `consumePurchase`) records the purchase token after the underlying acknowledge or consume call lands successfully against Play. The recovery channel still has `replay = 1` so the cache reflects current Play state, but `observePurchaseUpdates()` filters the cached snapshot against the acked-token set *at delivery time* (via a synchronous `map` that reads the current set per emission) — so even a late subscriber that attaches after you've already handled the purchase receives the cached sweep result re-filtered against the current acked set, not the stale pre-ack snapshot. Empty `Recovered` (intrinsic or filtered-to-empty) is dropped before delivery. There's nothing to dedupe in your collector:

```kotlin
is OwnedPurchases.Recovered -> event.purchases.forEach { purchase ->
    when (billing.handlePurchase(purchase, consume = false)) {  // or true for consumables
        HandlePurchaseResult.Success -> grantEntitlement(purchase)  // recovery is the whole point — actually grant
        HandlePurchaseResult.AlreadyAcknowledged -> {
            // Not reachable from a Recovered snapshot in practice — the sweep
            // pre-filters PURCHASED && !isAcknowledged, so the local
            // isAcknowledged flag is false and handlePurchase doesn't
            // short-circuit. Listed for exhaustiveness; the arm fires from
            // a manual queryPurchases reconciliation where you have a fresh
            // Purchase with isAcknowledged=true.
            grantEntitlement(purchase)
        }
        is HandlePurchaseResult.Failure -> {
            // Surface the error if you want, but DO NOT grant entitlement.
            // The library doesn't mark the token as acknowledged on Failure,
            // so the next sweep will surface this purchase again for retry.
        }
        HandlePurchaseResult.NotPurchased -> {}
    }
}
```

You should still treat the `Recovered` branch idempotently if you fire other one-shot UX off it (badge animations, analytics events, etc.) — but the `Set<String>` dedupe consumers used to need against `replay = 1` re-emission is no longer required. Tracking lives for the singleton repository's lifetime (typically the process), bounded by purchase activity; a fresh sweep on reconnect re-queries Play and surfaces only genuinely-unacked tokens. (Live events on the `OwnedPurchases.Live` branch don't need this — see "Replay semantics".)

```kotlin
billing.observePurchaseUpdates().collect { event ->
    when (event) {
        is OwnedPurchases.Live -> {
            event.purchases.forEach { handle(it) }
            fireConfetti() // user-initiated; celebrate
        }
        is OwnedPurchases.Recovered -> {
            // Same handle() call as Live — but no confetti. Background recovery,
            // not a fresh purchase. The library tracks acknowledged tokens internally
            // and suppresses replay of `Recovered` for already-handled purchases —
            // you don't need a consumer-side `Set<String>` dedupe.
            event.purchases.forEach { handle(it) }
        }
        is FlowOutcome -> { /* sub-when on Pending / Canceled / etc. */ }
    }
}
```

`Live` and `Recovered` are intentionally separate variants so you can branch your UX (don't show "thanks for your purchase!" on a sweep that ran when the user opened the app). The handle/grant code is identical for one-time products.

**Subscription replacements need special handling (until v0.2.0).** Subscription upgrade/downgrade/crossgrade purchases carry a non-null `linkedPurchaseToken` pointing at the prior subscription. Treating them as fresh grants double-grants entitlement on plan changes. PBL's `Purchase` API doesn't expose a getter for `linkedPurchaseToken` (`AccountIdentifiers` only carries `obfuscatedAccountId` / `obfuscatedProfileId`); the field is only present in `purchase.originalJson`. Until v0.2.0 ships the typed `SubscriptionReplacement` variant (see [`docs/ROADMAP.md`](docs/ROADMAP.md)), consumers using subscriptions need to parse it themselves:

```kotlin
fun Purchase.linkedPurchaseToken(): String? = try {
    org.json.JSONObject(originalJson)
        .optString("linkedPurchaseToken")
        .takeIf { it.isNotEmpty() }
} catch (e: org.json.JSONException) { null }
```

Treat a non-null result as a plan change (invalidate the old token, grant against the new one) rather than a fresh purchase. IAP-only apps are unaffected — one-time products never carry a `linkedPurchaseToken`.

To opt out (e.g. you run a server-side reconciliation queue):

```kotlin
val billing = BillingRepositoryCreator.create(
    context = applicationContext,
    recoverPurchasesOnConnect = false
)
```

## Granting entitlement: multi-quantity

Play supports multi-quantity purchases for consumables (the *Multi-quantity purchases* flag must be enabled per-product in Play Console; the user picks the quantity in the Play purchase dialog). Always grant `purchase.quantity` units, not 1 — and only grant after `handlePurchase` returns `Success`:

```kotlin
private suspend fun handle(purchase: Purchase) {
    when (val r = billing.handlePurchase(purchase, consume = true)) {
        HandlePurchaseResult.Success -> {
            if (purchase.products.contains("coins_pack")) {
                coinWallet.grant(amount = COINS_PER_PACK * purchase.quantity)
            }
        }
        HandlePurchaseResult.AlreadyAcknowledged -> {} // unreachable for consume=true (consumables aren't acked)
        HandlePurchaseResult.NotPurchased -> {} // pending; wait
        is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
        // never grant on Failure — recovery sweep retries on the next connection
    }
}
```

`purchase.quantity` defaults to `1` so single-unit code keeps working — but ignoring it on a multi-quantity purchase silently under-grants. The library handles the *acknowledgement* side correctly for any quantity (Play's consume API consumes the whole purchase regardless of unit count); only your entitlement-grant code needs the awareness.

## Replay semantics

`observePurchaseUpdates()` is internally a merge of two channels:

- **Live events** (`OwnedPurchases.Live` and every `FlowOutcome` variant — `Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse`) — `replay = 0`. A re-attached collector (configuration change, `repeatOnLifecycle`, ViewModel recreation) does **not** re-receive the previous live event. The entitlement grant and any one-shot UX (confetti, toasts, analytics) fired exactly once when the event arrived; replaying them on rotation would be a bug.
- **Recovery events** (`OwnedPurchases.Recovered`) — `replay = 1`. A late subscriber (one that attaches after the auto-sweep fired) catches the most recent recovery. This is what makes the recovery feature reliable in patterns where the consumer's collector races the connection coming up. The library tracks tokens passed through `acknowledgePurchase` / `consumePurchase` / `handlePurchase` and filters them out of subsequent `Recovered` emissions; `Recovered` events that filter to empty (or are intrinsically empty) are suppressed entirely. A re-subscribed collector that already handled the recovered purchase does not see the stale snapshot again.

You don't need to dedupe handle / grant / UX for live events — fire confetti directly from the `OwnedPurchases.Live` branch and you'll see it exactly once per purchase, even across rotations. You also don't need to dedupe the `OwnedPurchases.Recovered` branch for `replay = 1` re-emission of already-handled purchases (the library does that). Still treat `Recovered` idempotently if you trigger one-shot UX off it for other state-machine reasons (badge animations, analytics events, etc.).

## API overview

| Type | Role |
|---|---|
| `BillingRepositoryCreator.create(...)` | Public entry point. Returns `BillingRepository`. |
| `BillingRepository : BillingActions, BillingConnector, BillingPurchaseUpdatesOwner` | Composed interface — depend on the narrowest piece you need. |
| `BillingActions` | `queryPurchases`, `queryProductDetails`, `consumePurchase`, `acknowledgePurchase`, `handlePurchase`, `launchFlow`, `showInAppMessages`, `isFeatureSupported`. |
| `BillingConnector` | `connectToBilling(): SharedFlow<BillingConnectionResult>`. |
| `BillingPurchaseUpdatesOwner` | `observePurchaseUpdates(): Flow<PurchaseEvent>`. Hot internally; merges a no-replay live channel and a replay=1 recovery channel — see "Replay semantics". |
| `BillingException` (sealed) | 13 subtypes — 12 covering PBL response codes (each with a `RetryType` hint) plus `WrappedException` for non-PBL throwables surfaced through `handlePurchase`. |
| `BillingClientFactory` | Public test seam — swap `DefaultBillingClientFactory` to alter `BillingClient.Builder`. |
| `BillingLogger` | Pluggable logger (`Noop`, `Android`, or your own adapter). |

## Package layout

Where each public type lives. IDE auto-import handles most of these, but here's the canonical map:

| Subpackage | Contains |
|---|---|
| `com.kanetik.billing` | `BillingRepository`, `BillingRepositoryCreator`, `BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`, `BillingConnectionResult`, `PurchaseEvent`, `OwnedPurchases`, `FlowOutcome`, `HandlePurchaseResult`, `BillingInAppMessageResult`, `ProductDetailsQuery`, `RetryType`, `ResultStatus` |
| `com.kanetik.billing.exception` | `BillingException` (sealed) and its 13 subtypes; `BillingErrorCategory` enum |
| `com.kanetik.billing.logging` | `BillingLogger` interface + `Noop` + `Android` |
| `com.kanetik.billing.lifecycle` | `BillingConnectionLifecycleManager` |
| `com.kanetik.billing.factory` | `BillingClientFactory`, `DefaultBillingClientFactory` |
| `com.kanetik.billing.ext` | `validatePurchaseActivity`, `ProductDetails.toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseFlowResult` |
| `com.kanetik.billing.security` | `PurchaseVerifier` |

## Error handling

Most `BillingActions` methods that fail throw a typed `BillingException` subtype — `queryPurchases`, `queryProductDetails`, `queryProductDetailsWithUnfetched`, `consumePurchase`, `acknowledgePurchase`, `launchFlow`, `showInAppMessages`, `isFeatureSupported`. The high-level `handlePurchase` helper is the exception: it returns a sealed `HandlePurchaseResult` with a `Failure(BillingException)` variant instead (see "Handling `handlePurchase` failures correctly" above). The library's retry loop already retries transient failures (`SIMPLE_RETRY`, `EXPONENTIAL_RETRY`, `REQUERY_PURCHASE_RETRY`) up to three times with appropriate backoff before throwing — what reaches your `catch` (or your `Failure` branch for `handlePurchase`) is whatever didn't recover. `launchFlow` runs once with no retry, because UI-initiated purchases shouldn't silently retry behind the user.

For UI handling, branch on `userFacingCategory` (see "Showing errors to users" below). For lower-level branching, use the sealed subtype directly:

```kotlin
try {
    billing.queryProductDetails(params)
} catch (e: BillingException) {
    when (e) {
        is BillingException.NetworkErrorException,
        is BillingException.ServiceUnavailableException,
        is BillingException.ServiceDisconnectedException -> showRetryUI()
        is BillingException.BillingUnavailableException -> hideBillingFeatures()
        is BillingException.ItemUnavailableException -> showSoldOut()
        else -> reportToCrashlytics(e)
    }
}
```

`BillingException` subtypes:

| Subtype | When | Internal RetryType |
|---|---|---|
| `NetworkErrorException` | Lower-level network failure | `EXPONENTIAL_RETRY` |
| `ServiceUnavailableException` | Play Store service unreachable | `EXPONENTIAL_RETRY` |
| `ServiceDisconnectedException` | Client connection dropped mid-call | `SIMPLE_RETRY` |
| `FatalErrorException` | Generic Play Billing `ERROR` response code | `EXPONENTIAL_RETRY` |
| `ItemAlreadyOwnedException` | One-time product already owned | `REQUERY_PURCHASE_RETRY` |
| `ItemNotOwnedException` | Trying to consume something not in inventory | `REQUERY_PURCHASE_RETRY` |
| `BillingUnavailableException` | Play Store missing / disabled / wrong region | `NONE` |
| `ItemUnavailableException` | Product not configured for this user/country | `NONE` |
| `DeveloperErrorException` | API misuse — fix the code | `NONE` |
| `FeatureNotSupportedException` | Feature missing on this Play version | `NONE` |
| `UserCanceledException` | User dismissed the purchase flow | `NONE` |
| `UnknownException` | Response code PBL doesn't document — log it | `NONE` |
| `WrappedException` | Non-PBL throwable wrapped by `handlePurchase` (NPE, `IllegalStateException` from a custom `BillingActions` impl, `AssertionError` from a fake, etc.). Distinct from `UnknownException`; carries `originalCause` for diagnostics. | `NONE` |

`RetryType` is exposed on every exception via `e.retryType`, but you usually don't need to consult it directly — the library has already retried before throwing. The hint is there for diagnostics and for callers wanting to render "we'll try again automatically" messaging on the early throw paths.

### Showing errors to users

**Never display `BillingException.message` in your UI** — it's a debug-context dump (class name, response code, sub-response, debug message) intended for logs, Crashlytics, and dashboards. Showing it leaks internal Play strings like `ServiceDisconnectedException` and `BILLING_RESPONSE_CODE_3` into your dialogs.

For UI, branch on `BillingException.userFacingCategory` (returns a `BillingErrorCategory` — seven buckets: `UserCanceled`, `Network`, `BillingUnavailable`, `ProductUnavailable`, `AlreadyOwned`, `DeveloperError`, `Other`) and localize per bucket from your own string resources:

```kotlin
catch (e: BillingException) {
    when (e.userFacingCategory) {
        BillingErrorCategory.UserCanceled -> return  // not really an error
        BillingErrorCategory.AlreadyOwned -> restoreEntitlement()  // restore, don't error
        BillingErrorCategory.Network -> showError(getString(R.string.purchase_error_network))
        BillingErrorCategory.BillingUnavailable -> showError(getString(R.string.purchase_error_billing_unavailable))
        BillingErrorCategory.ProductUnavailable -> showError(getString(R.string.purchase_error_product_unavailable))
        BillingErrorCategory.DeveloperError,
        BillingErrorCategory.Other -> showError(getString(R.string.purchase_error_generic))
    }
    log.e("Billing failure", e)  // .message is fine here — it's a log
}
```

The library deliberately doesn't ship localized user-facing strings (tone, voice, and language coverage are app concerns).

### Handling `handlePurchase` failures correctly

`handlePurchase` returns a sealed `HandlePurchaseResult` — `Success`, `AlreadyAcknowledged`, `NotPurchased`, or `Failure(exception)`. The compiler nudges you to branch on each. **Grant entitlement on `Success` and `AlreadyAcknowledged` (both safe), nothing else** — Play auto-refunds the unacknowledged purchase within ~3 days and the user's premium silently evaporates if you grant on a `Failure` and the underlying ack call doesn't recover.

```kotlin
when (val r = billing.handlePurchase(purchase, consume = false)) {
    HandlePurchaseResult.Success -> grantPremium()
    HandlePurchaseResult.AlreadyAcknowledged -> grantPremium() // no PBL call made; safe
    HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
    is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
    // do NOT grant on Failure — the recovery sweep retries on next connect
}
```

The `AlreadyAcknowledged` variant fires when `consume = false` and the `Purchase` already has `isAcknowledged = true` — the library short-circuits before reaching out to Play. Treat it as entitlement-equivalent to `Success`; it exists as a separate variant so consumers can distinguish "we just acked" from "it was already done" for logging / metrics, and so `Failure` no longer overlaps with `Failure(DeveloperErrorException)` from a redundant acknowledge call. **Consumers can now safely untrack-on-Failure for retry on the next recovery sweep** — `Failure` unambiguously means a transient or terminal ack failure worth retrying, never an "already-acked, this will fail forever" loop. The `consume = true` path does not produce `AlreadyAcknowledged` — consumables aren't acked, they're consumed, and Play doesn't expose an `isConsumed` field on `Purchase` for a parallel check.

The auto-recovery sweep (see [`OwnedPurchases.Recovered`](#purchase-recovery)) re-emits the unacknowledged purchase on the next successful connection, so a transient `Failure` is recoverable; a granted-then-refunded purchase is not.

Lower-level `consumePurchase` and `acknowledgePurchase` still throw `BillingException` directly — callers at that layer are already in the weeds and a thrown exception is appropriate. `handlePurchase` is the high-level helper that gets the typed-result treatment because forgetting the failure case is the most common bug.

## Lifecycle integration

Wire `BillingConnectionLifecycleManager` to anything observable — an Activity, a Fragment, or `ProcessLifecycleOwner.get()` if you want process-wide warm connection.

```kotlin
class CheckoutActivity : ComponentActivity() {
    private val billing by lazy { BillingRepositoryCreator.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(BillingConnectionLifecycleManager(billing))
    }
}
```

The manager calls `connectToBilling()` on `onStart`, cancels any active collector on `onStop` (the underlying `WhileSubscribed(60_000)` keeps the connection warm for 60s after the last subscriber leaves), and cancels its `SupervisorJob` on `onDestroy`.

## Logging

Default is silent. Opt into logcat with one line, or wire your own adapter to route through Crashlytics, Timber, or whatever you prefer.

```kotlin
// Silent (default)
val billing = BillingRepositoryCreator.create(context)

// Logcat
val billing = BillingRepositoryCreator.create(context, logger = BillingLogger.Android)

// Crashlytics adapter (your code, ~10 lines).
// The library does not dictate a tag — pick one that fits your logging
// convention (here, "Billing"). If you omit `.tag(...)`, Timber falls back
// to the calling class name; android.util.Log requires a tag explicitly.
class CrashlyticsBillingLogger : BillingLogger {
    override fun d(message: String, throwable: Throwable?) {
        Timber.tag("Billing").d(throwable, message)
    }
    override fun w(message: String, throwable: Throwable?) {
        Timber.tag("Billing").w(throwable, message)
    }
    override fun e(message: String, throwable: Throwable?) {
        Timber.tag("Billing").e(throwable, message)
        FirebaseCrashlytics.getInstance().recordException(
            throwable ?: BillingLogException(message)
        )
    }
}
val billing = BillingRepositoryCreator.create(context, logger = CrashlyticsBillingLogger())
```

The library does not depend on Timber, Crashlytics, or any logging framework — that wiring is fully on the consumer side.

## Extensions (`com.kanetik.billing.ext`)

Patterns that most apps reimplement. Each one is `internal`-grade quality but optional — R8 strips the ones you don't use.

- **`validatePurchaseActivity(activity)`** — returns `true` if the activity is RESUMED, not finishing, not destroyed. Use as a precondition before `launchFlow`. The check uses `RESUMED` (not just `STARTED`) to handle the brief window after `onStart` where Play Billing's full-screen flow can still flake.
- **`ProductDetails.toOneTimeFlowParams(obfuscatedAccountId?, obfuscatedProfileId?, offerSelector?)`** — converts a one-time `ProductDetails` into a `BillingFlowParams`. `obfuscatedProfileId` is a secondary opaque ID for apps with multiple user profiles per install (rare; most apps only need `obfuscatedAccountId`). The `offerSelector` lambda exists for PBL 8's multi-offer one-time products (also rare); default picks the first offer.
- **`PurchaseFlowCoordinator`** — in-flight guard for purchase flow. `launch(activity, productDetails)` returns a sealed `PurchaseFlowResult`. A second launch while one is in flight returns `AlreadyInProgress` instead of opening a competing flow. Includes a `compareAndSet`-based watchdog that auto-clears the flag after a configurable timeout (default 2 minutes) so a stuck flow doesn't permanently block the user. Also accepts a configurable `uiDispatcher` constructor param for custom `BillingRepository` implementations or test overrides.

## Signature verification

`PurchaseVerifier` (in `com.kanetik.billing.security`) does RSA signature verification of `Purchase.originalJson` against your app's public key. The recommended integration:

```kotlin
val verifier = PurchaseVerifier(base64PublicKey = BuildConfig.PLAY_BILLING_PUBLIC_KEY)

// Sweep up OwnedPurchases (Live AND Recovered) — both carry purchases that
// need verifying and acknowledging. Filtering only Live would skip recovered
// purchases from a prior session, defeating the auto-recovery feature.
// FlowOutcome is excluded by design: those events describe attempt outcomes,
// not owned state — never grant from their `purchases` list.
billing.observePurchaseUpdates()
    .filterIsInstance<OwnedPurchases>()
    .collect { event ->
        event.purchases.forEach { purchase ->
            if (!verifier.isSignatureValid(purchase)) {
                logger.e(TAG, "Signature mismatch for ${purchase.products}")
                // Don't grant entitlement; consider reporting to your backend.
                return@forEach
            }
            // For Recovered events, dedupe by purchaseToken (see "Purchase recovery" above)
            // — this snippet shows just the verify-then-handle skeleton.
            when (val r = billing.handlePurchase(purchase, consume = false)) {
                HandlePurchaseResult.Success -> grantEntitlement(purchase)
                HandlePurchaseResult.AlreadyAcknowledged -> grantEntitlement(purchase) // safe — no PBL call made
                HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
                is HandlePurchaseResult.Failure -> {
                    // Don't grant — recovery sweep retries on the next clean connect.
                    logger.e(TAG, "handlePurchase failed: ${r.exception.userFacingCategory}")
                }
            }
        }
    }
```

`signatureAlgorithm` defaults to `SHA1withRSA` (PBL-current). Override only if you know what you're doing — PBL changes this rarely, and changing it without coordinated server-side changes will fail verification on every purchase.

## In-app messaging

Surface PBL 8's transactional messaging UI to prompt users to fix failed payment methods (typical for subscriptions, also reachable for IAP):

```kotlin
val result = billing.showInAppMessages(
    activity = this,
    params = InAppMessageParams.newBuilder()
        .addInAppMessageCategoryToShow(
            InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL
        )
        .build()
)
when (result) {
    is BillingInAppMessageResult.NoActionNeeded -> {} // common path
    is BillingInAppMessageResult.SubscriptionStatusUpdated -> {
        // The user fixed their payment method; refresh entitlement.
        refreshFromBackend(result.purchaseToken)
    }
}
```

The sealed wrapper means PBL's `InAppMessageResult` shape doesn't leak into your call sites or pin our ABI.

## Connection grace window

`connectToBilling()` is shared via `SharingStarted.WhileSubscribed(60_000)` — the connection stays alive for 60 seconds after the last subscriber unsubscribes. This is deliberate: it absorbs the typical configuration-change window (rotation, theme switch) without churning a fresh `BillingClient` connection on each transition.

If you need different timing, you can wrap the API yourself; a configurable grace window may surface as a creator parameter in a future release if a real consumer asks (see [`docs/ROADMAP.md`](docs/ROADMAP.md)).

## Subscriptions

v0.1.0 supports subscriptions at the *protocol* level — `queryPurchases`, `queryProductDetails`, `launchFlow` all accept `BillingClient.ProductType.SUBS`. What's missing in v0.1.0:

- Subscription offer-token selection helpers.
- Multi-line-item replacement helpers (`SubscriptionProductReplacementParams`).
- A subs sample in `/sample`.
- Subs-flavored docs.

Planned for v0.2.0 — see [`docs/ROADMAP.md`](docs/ROADMAP.md) for the full list of subs work pending. If you ship subscriptions on v0.1.0, you write the offer-token + replacement logic directly with PBL APIs; the rest of the library (connection, retry, error mapping, lifecycle, logging) still applies.

## Pre-order / multi-offer one-time products

PBL 8 lets a one-time product carry multiple offers (e.g., a pre-order discount alongside the regular price). The `toOneTimeFlowParams` extension takes an optional selector:

```kotlin
val params = productDetails.toOneTimeFlowParams(
    offerSelector = { offers ->
        offers.firstOrNull { it.offerId?.contains("preorder") == true }
            ?: offers.firstOrNull()
    }
)
```

Default selector picks the first offer. If your app has only one offer per product, you can ignore the parameter.

## Product-details caching

The library does not cache `ProductDetails`. Each `queryProductDetails` call hits Play. If you want session-level caching, wrap the query in a `StateFlow`:

```kotlin
class ProductCache(
    private val billing: BillingRepository,
    scope: CoroutineScope,
) {
    val products: StateFlow<List<ProductDetails>> = flow {
        emit(billing.queryProductDetails(params))
    }.stateIn(scope, SharingStarted.WhileSubscribed(60_000), emptyList())
}
```

This is the recommended pattern. Library-side caching would tax consumers with their own cache (most production apps) and muddle invalidation semantics.

## Real-Time Developer Notifications (RTDN)

RTDN is server-side — Cloud Pub/Sub from Play to your backend. The library is client-side only. For RTDN integration, see Google's [Real-time developer notifications guide](https://developer.android.com/google/play/billing/getting-ready#configure-rtdn).

If your backend posts notifications back to the client (e.g., "subscription state changed"), call `queryPurchases` to refresh and let the existing `observePurchaseUpdates` pipeline handle the result.

## Testing

Three levels of test realism, each appropriate for different work. See [`docs/TESTING.md`](docs/TESTING.md) for the deeper guide including Play Billing Lab usage.

### Level 1 — Static test SKUs (smoke / wiring)

```kotlin
val testProduct = QueryProductDetailsParams.Product.newBuilder()
    .setProductId("android.test.purchased")  // always succeeds, no UI
    .setProductType(BillingClient.ProductType.INAPP)
    .build()
```

Static SKUs (`android.test.purchased`, `android.test.canceled`, `android.test.item_unavailable`, `android.test.refunded`) work on any debug build with no Play Console configuration. They auto-complete with a fixed result — useful for verifying your `observePurchaseUpdates` collector and `handlePurchase` calls fire correctly. **They do not show the Play purchase dialog**; that's by design.

### Level 2 — Real product + license tester (real dialog)

This is what you need to actually see the Play purchase dialog and exercise the full flow.

1. **Get the app onto Play Console** (any track — Internal testing is fine, no promotion required).
2. **Create a managed in-app product** at Monetize → Products → In-app products → Create product. Set Product ID, type One-time, any price (you won't be charged), state Active.
3. **Add yourself as a license tester** at Setup → License testing.
4. **Install the debug build** on a device signed in with the license-tester Google account. Use your real product ID in `QueryProductDetailsParams`.
5. Tap Buy → the real Play dialog appears. License-tester purchases auto-refund within 48 hours.

### Level 3 — Play Billing Lab (edge-case scenarios on top of Level 2)

[Play Billing Lab](https://developer.android.com/google/play/billing/test#play-billing-lab) is Google's testing companion app. Install it alongside your test app once Level 2 works.

What Lab does:
- **Response simulator** — force any `BillingResult` response code (e.g. `USER_CANCELED`, `BILLING_UNAVAILABLE`, `NETWORK_ERROR`) for any flow. Lets you verify each `BillingException` subtype's handling without engineering real failures.
- **Configuration settings** — override region, exercise free trials and intro offers on existing products.
- **Subscription settings** — test grace period, account hold, and price-change flows on subscriptions you've already configured.

What Lab does *not* do: it doesn't fabricate products from nothing. The products it serves come from your Play Console configuration. Lab augments Level 2; it doesn't replace it.

### Debug-flavor entitlement override

Most consumer apps wrap the repository with a debug-flavor stub for offline development:

```kotlin
// debug/.../PremiumManager.kt
class PremiumManager @Inject constructor(/* ... */) {
    val isPremium: StateFlow<Boolean> = MutableStateFlow(BuildConfig.DEBUG_PREMIUM_OVERRIDE)
    /* ... */
}
```

A first-class `:billing-testing` artifact with a `FakeBillingRepository` (in-memory, scriptable) is planned for v0.2.0 — see [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Hilt DI snippet

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideBillingLogger(): BillingLogger = BillingLogger.Android  // or your own adapter

    @Provides
    @Singleton
    fun provideBillingRepository(
        @ApplicationContext context: Context,
        logger: BillingLogger,
    ): BillingRepository = BillingRepositoryCreator.create(
        context = context,
        logger = logger,
    )
}
```

For Koin, the same shape works with `single { BillingRepositoryCreator.create(...) }`.

## Limitations

The following are intentionally out of scope for v0.1.0 — see [`docs/ROADMAP.md`](docs/ROADMAP.md) for what's planned:

- **Subscription-specific helpers, samples, and docs** — protocol-level pass-through works; rich helpers come in v0.2.0.
- **External offers / alternative billing** — apps that need
  `BillingClient.Builder.enableBillingProgram(...)` provide their own
  `BillingClientFactory` impl. First-class support is demand-driven.
- **Pre-order full lifecycle helpers** — accessible through `ProductDetails`,
  but no dedicated helpers in v0.1.0. v0.2.0 docs cover the manual path.
- **`:billing-testing` artifact** — a `FakeBillingRepository` for unit tests +
  debug-flavor DI overrides is planned for v0.2.0.

## Documentation

- [`docs/TESTING.md`](docs/TESTING.md) — three levels of test realism (static SKUs / license tester / Play Billing Lab) plus consumer-code testing patterns.
- [`docs/BUILD_HISTORY.md`](docs/BUILD_HISTORY.md) — design rationale and decisions for v0.1.0.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — what's next.

## License

Apache-2.0 — see [LICENSE](LICENSE).

This project is a substantial rewrite of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy) (Apache-2.0). See [NOTICE](NOTICE) for full attribution.
