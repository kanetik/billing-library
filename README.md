# Kanetik Billing Library

A coroutine-first wrapper around [Google Play Billing Library 8.x](https://developer.android.com/google/play/billing). Typed errors with retry-type hints, lifecycle-aware connection sharing, exponential backoff, and opt-in helpers for the patterns most apps reimplement themselves.

## Why

- **Less boilerplate** — `connectToBilling()`, `queryProductDetails(...)`, `launchFlow(...)`, `observePurchaseUpdates()` are coroutine-flavored equivalents of PBL's listener/callback APIs. No `BillingClientStateListener`, no `PurchasesUpdatedListener` wiring at the call site.
- **Typed errors** — every `BillingResponseCode` lands as a `BillingException` subtype carrying a `RetryType` hint. Branch on the type, not on integers.
- **Lifecycle-aware** — `BillingConnectionLifecycleManager` keeps the connection warm while an activity/process is observable and tears it down on destruction, with a 60-second grace window to absorb configuration changes.

## Installation

```kotlin
dependencies {
    implementation("com.kanetik.billing:billing:0.1.0")
}
```

Requires `minSdk = 23` (PBL 8.1's floor — the library pins to PBL 8.3.0). JVM target is 11.

## Quick start (one-time IAP)

```kotlin
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.kanetik.billing.BillingRepositoryCreator
import com.kanetik.billing.PurchasesUpdate
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

        // Observe purchase results from the global PurchasesUpdatedListener.
        lifecycleScope.launch {
            billing.observePurchaseUpdates().collect { update ->
                when (update) {
                    is PurchasesUpdate.Success -> update.purchases.forEach { handle(it) }
                    is PurchasesUpdate.Recovered -> update.purchases.forEach { handle(it) } // see "Purchase recovery" below
                    is PurchasesUpdate.Pending -> showPendingNotice() // do NOT grant entitlement yet
                    is PurchasesUpdate.Canceled -> {}
                    is PurchasesUpdate.ItemAlreadyOwned -> restoreEntitlement()
                    is PurchasesUpdate.ItemUnavailable -> showSoldOut()
                    is PurchasesUpdate.UnknownResponse -> reportFailure(update.code)
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

    private suspend fun handle(purchase: Purchase) {
        // handlePurchase returns a sealed HandlePurchaseResult — branch on it.
        // See "Handling handlePurchase failures correctly" below for the full pattern.
        when (val r = billing.handlePurchase(purchase, consume = false)) {
            HandlePurchaseResult.Success -> grantPremium()
            HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
            is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
        }
    }
}
```

That's enough for a working one-time-IAP integration. Subscriptions work at the protocol level via raw `QueryPurchasesParams` + `BillingFlowParams`; subscription-specific helpers ship in v0.2.0 (see [`docs/ROADMAP.md`](docs/ROADMAP.md)).

## Purchase recovery

Play auto-refunds purchases that aren't acknowledged within 3 days. App crashes, network failures, or process death mid-acknowledge can strand a paid purchase — without recovery, the user pays, gets refunded, and never sees the entitlement.

The library handles this for you. On every successful Play Billing connection (app start, returning from background, post-disconnect reconnect), it queries owned `INAPP` + `SUBS` purchases, filters for `PURCHASED && !isAcknowledged`, and emits any matches as `PurchasesUpdate.Recovered`. Your existing `observePurchaseUpdates()` collector picks them up — no new code, no startup hook.

This requires that *something* is driving the connection. The standard pattern uses `BillingConnectionLifecycleManager` (see "Lifecycle integration" below), which collects `connectToBilling()` while a `LifecycleOwner` is started and triggers the recovery sweep automatically. Subscribing to `observePurchaseUpdates()` alone does **not** open the connection; pair it with the lifecycle manager (or your own `connectToBilling()` collector) so the sweep can fire. The `MutableSharedFlow` backing `observePurchaseUpdates()` keeps `replay = 1`, so a subscriber that attaches a moment after the sweep still receives the recovered purchases.

```kotlin
billing.observePurchaseUpdates().collect { update ->
    when (update) {
        is PurchasesUpdate.Success -> {
            update.purchases.forEach { handle(it) }
            fireConfetti() // user-initiated; celebrate
        }
        is PurchasesUpdate.Recovered -> {
            // Same handle() call — but no confetti. Background recovery, not a fresh purchase.
            update.purchases.forEach { handle(it) }
        }
        // ... other arms
    }
}
```

`Success` and `Recovered` are intentionally separate variants so you can branch your UX (don't show "thanks for your purchase!" on a sweep that ran when the user opened the app). The handle/grant code is identical.

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
        HandlePurchaseResult.NotPurchased -> {} // pending; wait
        is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
        // never grant on Failure — recovery sweep retries on the next connection
    }
}
```

`purchase.quantity` defaults to `1` so single-unit code keeps working — but ignoring it on a multi-quantity purchase silently under-grants. The library handles the *acknowledgement* side correctly for any quantity (Play's consume API consumes the whole purchase regardless of unit count); only your entitlement-grant code needs the awareness.

## Re-subscription and replay

The flow backing `observePurchaseUpdates()` uses `replay = 1` so that a `PurchasesUpdate.Recovered` emission from the auto-sweep isn't lost if the consumer's collector attaches a moment after the connection comes up. The trade-off: **the most recent emission is replayed to every new subscriber**, including a subscriber that re-attaches during a configuration change (`repeatOnLifecycle`, ViewModel recreation, etc.).

Two replay-aware behaviors to plan around:

1. **Acknowledge is idempotent; consume is not fully.** Re-acknowledging an already-acknowledged purchase short-circuits via `Purchase.isAcknowledged`. Re-consuming an already-consumed consumable returns `HandlePurchaseResult.Failure(ItemNotOwnedException)` — Play has no record of the purchase to consume. Treat `ItemNotOwnedException` on a consume attempt as the already-handled signal it is, not a real error.
2. **UI side effects are not idempotent.** Confetti, "thanks for your purchase!" toasts, and analytics events will fire each time a re-subscribed collector receives the replayed event.

Recommended pattern — dedupe both the handle call and any one-shot UX by `purchaseToken`:

```kotlin
private val handledTokens = MutableStateFlow<Set<String>>(emptySet())
private val celebratedTokens = MutableStateFlow<Set<String>>(emptySet())

billing.observePurchaseUpdates().collect { update ->
    when (update) {
        is PurchasesUpdate.Success -> update.purchases.forEach { purchase ->
            if (purchase.purchaseToken in handledTokens.value) return@forEach
            when (val r = billing.handlePurchase(purchase, consume = true)) {
                HandlePurchaseResult.Success -> {
                    handledTokens.update { it + purchase.purchaseToken }
                    if (purchase.purchaseToken !in celebratedTokens.value) {
                        fireConfetti()
                        celebratedTokens.update { it + purchase.purchaseToken }
                    }
                }
                HandlePurchaseResult.NotPurchased -> {} // pending
                is HandlePurchaseResult.Failure -> when (r.exception) {
                    is BillingException.ItemNotOwnedException -> {
                        // Replayed Success for an already-consumed purchase. Mark token
                        // so subsequent replays skip the consume call entirely.
                        handledTokens.update { it + purchase.purchaseToken }
                    }
                    else -> showError(r.exception.userFacingCategory)
                }
            }
        }
        is PurchasesUpdate.Recovered -> update.purchases.forEach(::handle)  // never fire confetti for recovery
        else -> {}
    }
}
```

Persist `handledTokens` and `celebratedTokens` (e.g. via `SavedStateHandle` or a small preferences entry) if you need the dedupe to survive process death.

The cleaner architectural fix — splitting recovery state into a `StateFlow<List<Purchase>>` with `replay = 0` for the live `SharedFlow` — is captured in `docs/ROADMAP.md` and would eliminate this caveat entirely. Until a real consumer reports the re-fire bug, the dedupe pattern is the working answer.

## API overview

| Type | Role |
|---|---|
| `BillingRepositoryCreator.create(...)` | Public entry point. Returns `BillingRepository`. |
| `BillingRepository : BillingActions, BillingConnector, BillingPurchaseUpdatesOwner` | Composed interface — depend on the narrowest piece you need. |
| `BillingActions` | `queryPurchases`, `queryProductDetails`, `consumePurchase`, `acknowledgePurchase`, `handlePurchase`, `launchFlow`, `showInAppMessages`, `isFeatureSupported`. |
| `BillingConnector` | `connectToBilling(): SharedFlow<BillingConnectionResult>`. |
| `BillingPurchaseUpdatesOwner` | `observePurchaseUpdates(): SharedFlow<PurchasesUpdate>`. |
| `BillingException` (sealed) | 12 subtypes; one per response code. Each carries a `RetryType` hint. |
| `BillingClientFactory` | Public test seam — swap `DefaultBillingClientFactory` to alter `BillingClient.Builder`. |
| `BillingLogger` | Pluggable logger (`Noop`, `Android`, or your own adapter). |

## Package layout

Where each public type lives. IDE auto-import handles most of these, but here's the canonical map:

| Subpackage | Contains |
|---|---|
| `com.kanetik.billing` | `BillingRepository`, `BillingRepositoryCreator`, `BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`, `BillingConnectionResult`, `PurchasesUpdate`, `HandlePurchaseResult`, `BillingInAppMessageResult`, `ProductDetailsQuery`, `RetryType`, `ResultStatus` |
| `com.kanetik.billing.exception` | `BillingException` (sealed) and its 12 subtypes; `BillingErrorCategory` enum |
| `com.kanetik.billing.logging` | `BillingLogger` interface + `Noop` + `Android` |
| `com.kanetik.billing.lifecycle` | `BillingConnectionLifecycleManager` |
| `com.kanetik.billing.factory` | `BillingClientFactory`, `DefaultBillingClientFactory` |
| `com.kanetik.billing.ext` | `validatePurchaseActivity`, `ProductDetails.toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseFlowResult` |
| `com.kanetik.billing.security` | `PurchaseVerifier` |

## Error handling

Every `BillingActions` method that fails throws a typed `BillingException` subtype. The library's retry loop already retries transient failures (`SIMPLE_RETRY`, `EXPONENTIAL_RETRY`, `REQUERY_PURCHASE_RETRY`) up to three times with appropriate backoff before throwing — what reaches your `catch` is whatever didn't recover. `launchFlow` is the exception: it runs once, because UI-initiated purchases shouldn't silently retry behind the user.

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

`RetryType` is exposed on every exception via `e.retryType`, but you usually don't need to consult it directly — the library has already retried before throwing. The hint is there for diagnostics and for callers wanting to render "we'll try again automatically" messaging on the early throw paths.

### Showing errors to users

**Never display `BillingException.message` in your UI** — it's a debug-context dump (class name, response code, sub-response, debug message) intended for logs, Crashlytics, and dashboards. Showing it leaks internal Play strings like `ServiceDisconnectedException` and `BILLING_RESPONSE_CODE_3` into your dialogs.

For UI, branch on `BillingException.userFacingCategory` (returns a `BillingErrorCategory` — six buckets: `UserCanceled`, `Network`, `BillingUnavailable`, `ProductUnavailable`, `DeveloperError`, `Other`) and localize per bucket from your own string resources:

```kotlin
catch (e: BillingException) {
    val msgRes = when (e.userFacingCategory) {
        BillingErrorCategory.UserCanceled -> return  // not really an error
        BillingErrorCategory.Network -> R.string.purchase_error_network
        BillingErrorCategory.BillingUnavailable -> R.string.purchase_error_billing_unavailable
        BillingErrorCategory.ProductUnavailable -> R.string.purchase_error_product_unavailable
        BillingErrorCategory.DeveloperError -> R.string.purchase_error_generic
        BillingErrorCategory.Other -> R.string.purchase_error_generic
    }
    showError(getString(msgRes))
    log.e("Billing failure", e)  // .message is fine here — it's a log
}
```

The library deliberately doesn't ship localized user-facing strings (tone, voice, and language coverage are app concerns).

### Handling `handlePurchase` failures correctly

`handlePurchase` returns a sealed `HandlePurchaseResult` — `Success`, `NotPurchased`, or `Failure(exception)`. The compiler nudges you to branch on each. **Don't grant entitlement outside the `Success` branch** — Play auto-refunds the unacknowledged purchase within ~3 days and the user's premium silently evaporates.

```kotlin
when (val r = billing.handlePurchase(purchase, consume = false)) {
    HandlePurchaseResult.Success -> grantPremium()
    HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
    is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
    // do NOT grant on Failure — the recovery sweep retries on next connect
}
```

The auto-recovery sweep (see [`PurchasesUpdate.Recovered`](#purchase-recovery)) re-emits the unacknowledged purchase on the next successful connection, so a transient `Failure` is recoverable; a granted-then-refunded purchase is not.

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
- **`ProductDetails.toOneTimeFlowParams(obfuscatedAccountId?, offerSelector?)`** — converts a one-time `ProductDetails` into a `BillingFlowParams`. The `offerSelector` lambda exists for PBL 8's multi-offer one-time products (rare but supported); default picks the first offer.
- **`PurchaseFlowCoordinator`** — in-flight guard for purchase flow. `launch(activity, productDetails)` returns a sealed `PurchaseFlowResult`. A second launch while one is in flight returns `AlreadyInProgress` instead of opening a competing flow. Includes a `compareAndSet`-based watchdog that auto-clears the flag after a configurable timeout (default 5 min) so a stuck flow doesn't permanently block the user.

## Signature verification

`PurchaseVerifier` (in `com.kanetik.billing.security`) does RSA signature verification of `Purchase.originalJson` against your app's public key. The recommended integration:

```kotlin
val verifier = PurchaseVerifier(base64PublicKey = BuildConfig.PLAY_BILLING_PUBLIC_KEY)

billing.observePurchaseUpdates()
    .filterIsInstance<PurchasesUpdate.Success>()
    .collect { update ->
        update.purchases.forEach { purchase ->
            if (verifier.isSignatureValid(purchase)) {
                billing.handlePurchase(purchase, consume = false)
            } else {
                logger.e(TAG, "Signature mismatch for ${purchase.products}")
                // Don't grant entitlement; consider reporting to your backend.
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
