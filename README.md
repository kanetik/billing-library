# Kanetik Billing Library

A coroutine-first wrapper around [Google Play Billing Library 8.x](https://developer.android.com/google/play/billing). Typed errors with retry-type hints, lifecycle-aware connection sharing, exponential backoff, and opt-in helpers for the patterns most apps reimplement themselves.

## Why

- The coroutine wrappers — `connectToBilling()`, `queryProductDetails(...)`, `launchFlow(...)`, `observePurchaseUpdates()` — replace PBL's listener/callback wiring at the call site. `observePurchaseUpdates()` returns a `Flow<PurchaseEvent>` split into two sealed roots: `OwnedPurchases` (`Live`, `Recovered`) for owned-state updates and `FlowOutcome` (`Pending`, `Canceled`, etc.) for purchase-flow attempt outcomes. The split is what stops you from accidentally writing a `Canceled` event's `purchases` list into your entitlement cache.
- Every `BillingResponseCode` lands as a typed `BillingException` subtype with a `RetryType` hint. Branch on the type, not on integers.
- `BillingConnectionLifecycleManager` keeps the connection warm while an activity (or process) is observable and tears it down on destruction. There's a 60-second grace window so configuration changes don't churn the connection.

## Installation

```kotlin
dependencies {
    implementation("com.kanetik.billing:billing:0.1.1")
}
```

Requires `minSdk = 23` (PBL 8.1's floor — the library pins to PBL 8.3.0). JVM target is 11. See [Installation](https://kanetik.github.io/billing-library/installation/) for Groovy / version catalog / Maven variants.

## Quick start (one-time IAP)

(Standard AndroidX / coroutines imports — `ComponentActivity`, `Bundle`, `lifecycleScope`, `kotlinx.coroutines.launch` — are omitted for readability; only the library's own imports are listed.)

```kotlin
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.kanetik.billing.BillingRepositoryCreator
import com.kanetik.billing.FlowOutcome
import com.kanetik.billing.HandlePurchaseResult
import com.kanetik.billing.OwnedPurchases
import com.kanetik.billing.PurchaseRevoked
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

        // Observe purchase events. The library's BillingClientStorage tracks
        // acknowledged tokens internally and filters them out of `Recovered`
        // at delivery time, so already-handled recovered purchases aren't
        // re-delivered on re-subscribe — no consumer-side dedupe needed.
        lifecycleScope.launch {
            billing.observePurchaseUpdates().collect { event ->
                when (event) {
                    is OwnedPurchases.Live -> event.purchases.forEach { handle(it) }
                    is OwnedPurchases.Recovered -> event.purchases.forEach { handle(it) }
                    is FlowOutcome.Pending -> showPendingNotice() // do NOT grant entitlement yet
                    is FlowOutcome.Canceled -> {}
                    is FlowOutcome.ItemAlreadyOwned -> restoreEntitlement()
                    is FlowOutcome.ItemUnavailable -> showSoldOut()
                    is FlowOutcome.Failure -> showError(event.exception.userFacingCategory)
                    is FlowOutcome.UnknownResponse -> reportFailure(event.code)
                    is PurchaseRevoked -> revokeEntitlement(event.purchaseToken, event.reason)
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
        when (val r = billing.handlePurchase(purchase, consume = false)) {
            HandlePurchaseResult.Success -> grantPremium()
            HandlePurchaseResult.AlreadyAcknowledged -> grantPremium() // safe — no PBL call needed
            HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
            HandlePurchaseResult.NotOwned -> {} // Play says not owned — defer to grace/revoke logic
            is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
            // do NOT grant on Failure — recovery sweep retries on the next connection
        }
    }
}
```

That's enough for a working one-time-IAP integration. Subscriptions work at the protocol level via raw `QueryPurchasesParams` + `BillingFlowParams`; subscription-specific helpers ship in v0.2.0 (see the [Roadmap](https://kanetik.github.io/billing-library/roadmap/)).

> **⚠️ Two-tier `PurchaseEvent` — read before writing to your cache**
>
> `observePurchaseUpdates()` emits `PurchaseEvent`, a marker interface with
> two sealed roots:
>
> - **`OwnedPurchases`** (`Live`, `Recovered`) — owned-state updates. The
>   user owns these purchases; acknowledge / consume / grant entitlement.
>   These are **incremental updates, not authoritative owned-state
>   snapshots** — `Live` carries the `PURCHASED`-or-`UNSPECIFIED_STATE`
>   subset of an `OK` callback, and `Recovered` carries only the
>   unacknowledged subset from the auto-sweep. Both channels are filtered
>   to non-empty before delivery (PBL occasionally fires the listener with
>   no purchases at all; the library drops those at the source). Merge
>   into your own entitlement state on `handlePurchase` Success rather
>   than replacing your cache from `event.purchases`. For managed
>   entitlement state with grace policy, use `EntitlementCache`.
> - **`FlowOutcome`** (`Pending`, `Canceled`, `ItemAlreadyOwned`,
>   `ItemUnavailable`, `Failure`, `UnknownResponse`) — purchase-flow attempt outcomes.
>   These describe what *happened* on a single launch attempt. The
>   `purchases` list is typically empty (or, for `Pending`, purchases
>   that haven't completed yet) and **must not** be written to an
>   entitlement cache.
>
> The marker interface doesn't expose `purchases` directly — you have to
> narrow to `OwnedPurchases` or `FlowOutcome` first. That's what prevents
> the original bug: writing `update.purchases` from a `Canceled` (or other
> `FlowOutcome`) event into your entitlement cache. Note:
> `OwnedPurchases.purchases` still isn't an authoritative owned-state
> snapshot — see each variant's KDoc for the actual shape.

## Where to go next

- **[Full guide](https://kanetik.github.io/billing-library/)** — purchase recovery, error handling, `EntitlementCache` (with signed/tamper-resistant storage), signature verification, server-driven revocation, lifecycle wiring, logging adapters, multi-quantity grants, in-app messaging, subscriptions, and more.
- **[API reference](https://kanetik.github.io/billing-library/api/)** — Dokka-generated KDoc for every public type.
- **[Roadmap](https://kanetik.github.io/billing-library/roadmap/)** — what's next (subscription helpers, `:billing-testing` artifact, more).
- **[Testing guide](https://kanetik.github.io/billing-library/testing/)** — the three-levels approach (static SKUs / license tester / Play Billing Lab).
- **[Changelog](CHANGELOG.md)** — release notes.
- **[Sample app](sample/README.md)** — a runnable Compose + ViewModel integration in `:sample`.

## License

Apache-2.0 — see [LICENSE](LICENSE).

This project is a substantial rewrite of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy) (Apache-2.0). See [NOTICE](NOTICE) for full attribution.
