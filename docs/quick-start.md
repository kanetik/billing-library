# Quick start (one-time IAP)

A complete one-time-IAP integration. Subscriptions work at the protocol level via raw `QueryPurchasesParams` + `BillingFlowParams`; subscription-specific helpers ship in v0.2.0 — see the [Roadmap](roadmap.md).

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

## What's worth reading next

A few topics are worth picking up before you ship — especially if Play Billing is new to you. The order below isn't strict, but it's the order that builds context if you read sequentially:

- [Purchase recovery](guides/purchase-recovery.md) — what `PurchaseEvent`'s two tiers actually are (and why splitting them matters), why acknowledgement is a three-day cliff that costs real money if you miss it, and what the library's auto-sweep does for you. Probably the most important read in the docs.
- [Error handling](guides/error-handling.md) — typed exceptions, the seven `BillingErrorCategory` UI buckets, and the retry strategy the library runs before throwing.
- [EntitlementCache](guides/entitlement-cache.md) — opt-in state machine that answers "is the user entitled right now," with a grace window for transient Play outages and signed/tamper-resistant storage if your threat model needs it.
- [Signature verification](guides/signature-verification.md) — proving an incoming `Purchase` actually came from Google.
- [Testing](testing.md) — the three-levels approach (static SKUs / license tester / Play Billing Lab).
