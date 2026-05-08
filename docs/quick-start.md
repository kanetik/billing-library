# Quick start (one-time IAP)

A complete one-time-IAP integration. Subscriptions work at the protocol level via raw `QueryPurchasesParams` + `BillingFlowParams`; subscription-specific helpers ship in v0.2.0 — see the [Roadmap](roadmap.md).

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
        // handlePurchase returns a sealed HandlePurchaseResult — branch on it.
        // See Error handling for the full pattern.
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

That's enough for a working one-time-IAP integration.

!!! warning "Two-tier `PurchaseEvent` — read before writing to your cache"

    `observePurchaseUpdates()` emits `PurchaseEvent`, a marker interface with two sealed roots:

    - **`OwnedPurchases`** (`Live`, `Recovered`) — owned-state updates. The user owns these purchases; acknowledge / consume / grant entitlement. These are **incremental updates, not authoritative owned-state snapshots** — `Live` carries the `PURCHASED`-or-`UNSPECIFIED_STATE` subset of an `OK` callback, and `Recovered` carries only the unacknowledged subset from the auto-sweep. Both channels are filtered to non-empty before delivery (PBL occasionally fires the listener with no purchases at all; the library drops those at the source). Merge into your own entitlement state on `handlePurchase` Success rather than replacing your cache from `event.purchases`. For managed entitlement state with grace policy, use [`EntitlementCache`](guides/entitlement-cache.md).
    - **`FlowOutcome`** (`Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse`) — purchase-flow attempt outcomes. These describe what *happened* on a single launch attempt. The `purchases` list is typically empty (or, for `Pending`, purchases that haven't completed yet) and **must not** be written to an entitlement cache.

    The marker interface doesn't expose `purchases` directly — you have to narrow to `OwnedPurchases` or `FlowOutcome` first. That's what prevents the original bug: writing `update.purchases` from a `Canceled` (or other `FlowOutcome`) event into your entitlement cache. Note: `OwnedPurchases.purchases` still isn't an authoritative owned-state snapshot — see each variant's KDoc for the actual shape.

## Next steps

- [Purchase recovery](guides/purchase-recovery.md) — what happens when an acknowledge lands the next time the connection comes up
- [Error handling](guides/error-handling.md) — `BillingException` subtypes, `userFacingCategory`, the `HandlePurchaseResult` branches
- [EntitlementCache](guides/entitlement-cache.md) — opt-in state machine for "is the user entitled right now?"
- [Testing](testing.md) — the three-levels approach (static SKUs / license tester / Play Billing Lab)
