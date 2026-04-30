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
        billing.handlePurchase(purchase, consume = false) // acknowledges non-consumables
    }
}
```

That's enough for a working one-time-IAP integration. Subscriptions work at the protocol level via raw `QueryPurchasesParams` + `BillingFlowParams`; subscription-specific helpers ship in v0.2.0 (see [`docs/ROADMAP.md`](docs/ROADMAP.md)).

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
| `com.kanetik.billing` | `BillingRepository`, `BillingRepositoryCreator`, `BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`, `BillingConnectionResult`, `PurchasesUpdate`, `BillingInAppMessageResult`, `ProductDetailsQuery`, `RetryType`, `ResultStatus` |
| `com.kanetik.billing.exception` | `BillingException` (sealed) and its 12 subtypes |
| `com.kanetik.billing.logging` | `BillingLogger` interface + `Noop` + `Android` |
| `com.kanetik.billing.lifecycle` | `BillingConnectionLifecycleManager` |
| `com.kanetik.billing.factory` | `BillingClientFactory`, `DefaultBillingClientFactory` |
| `com.kanetik.billing.ext` | `validatePurchaseActivity`, `ProductDetails.toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseFlowResult` |
| `com.kanetik.billing.security` | `PurchaseVerifier` |

## Error handling

```kotlin
try {
    billing.queryProductDetails(params)
} catch (e: BillingException) {
    when (e.retryType) {
        RetryType.SAFE -> retryWithBackoff()
        RetryType.UNSAFE -> surfaceToUserAndStop()
        RetryType.NEVER -> logAndGiveUp()
    }
}
```

`BillingException` subtypes:

| Subtype | When | RetryType |
|---|---|---|
| `ServiceDisconnectedException` | Client connection dropped mid-call | SAFE |
| `ServiceUnavailableException` | Network unavailable, or 30s connection timeout | SAFE |
| `ServiceTimeoutException` | PBL signaled timeout | SAFE |
| `BillingUnavailableException` | Play Store missing / disabled / wrong region | UNSAFE |
| `ItemUnavailableException` | Product not configured for this user/country | UNSAFE |
| `ItemAlreadyOwnedException` | One-time product already owned | UNSAFE |
| `ItemNotOwnedException` | Trying to consume something not in inventory | UNSAFE |
| `DeveloperErrorException` | API misuse — fix the code | NEVER |
| `FeatureNotSupportedException` | Feature missing on this Play version | NEVER |
| `ErrorException` | Generic ERROR response code | UNSAFE |
| `UserCanceledException` | User dismissed the purchase flow | NEVER |
| `NetworkErrorException` | Lower-level network failure | SAFE |

The library's internal retry loop already retries `RetryType.SAFE` failures up to 3 times with exponential backoff, *except* for `launchFlow` (which runs once — UI-initiated purchases shouldn't silently retry behind the user). What you wrap in retry-vs-surface logic is whatever leaks out.

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

// Crashlytics adapter (your code, ~10 lines)
class CrashlyticsBillingLogger : BillingLogger {
    override fun d(tag: String, message: String) = Timber.tag(tag).d(message)
    override fun w(tag: String, message: String, t: Throwable?) {
        Timber.tag(tag).w(t, message)
    }
    override fun e(tag: String, message: String, t: Throwable?) {
        Timber.tag(tag).e(t, message)
        FirebaseCrashlytics.getInstance().recordException(t ?: BillingLogException(message))
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

### Static test SKUs (no Play Console setup)

```kotlin
val testProduct = QueryProductDetailsParams.Product.newBuilder()
    .setProductId("android.test.purchased")  // always succeeds
    .setProductType(BillingClient.ProductType.INAPP)
    .build()
```

Other static SKUs: `android.test.canceled`, `android.test.item_unavailable`, `android.test.refunded`. They work on any debug build with no Play Console configuration.

### Play Billing Lab

Google's testing companion app — region overrides, time-acceleration, real-payment-method mode. Install [Play Billing Lab](https://developer.android.com/google/play/billing/test#play-billing-lab) on the same device as your app for richer testing scenarios than static SKUs allow.

### License testers (real SKUs)

For real-product testing without charging your card:

1. Add a tester account at Play Console → **Setup → License testing**.
2. Upload your app to the **Internal testing** track and join from the tester device.
3. Buy with the tester account — purchases auto-refund within 48 hours.

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

- [`docs/BUILD_HISTORY.md`](docs/BUILD_HISTORY.md) — design rationale and decisions for v0.1.0.
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — what's next.

## License

Apache-2.0 — see [LICENSE](LICENSE).

This project is a substantial rewrite of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy) (Apache-2.0). See [NOTICE](NOTICE) for full attribution.
