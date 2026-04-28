package com.kanetik.billing

import android.app.Activity
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.kanetik.billing.exception.BillingException

/**
 * Suspend-style operations against Google Play Billing.
 *
 * Every method waits for the underlying [com.android.billingclient.api.BillingClient]
 * connection (see [BillingConnector]), runs with internal retry / backoff for transient
 * failures, and surfaces hard failures as a typed [BillingException] subtype so
 * consumers can branch by [com.kanetik.billing.RetryType] without parsing strings.
 */
public interface BillingActions {

    /**
     * @return true if the underlying [BillingClient] reports the [feature] is supported
     * on the current device + Play Store install.
     */
    @AnyThread
    public suspend fun isFeatureSupported(@BillingClient.FeatureType feature: String): Boolean

    /**
     * Returns the user's currently-owned purchases for the [params]-specified product type.
     * Pass [BillingClient.ProductType.INAPP] for one-time products,
     * [BillingClient.ProductType.SUBS] for subscriptions.
     */
    @AnyThread
    public suspend fun queryPurchases(params: QueryPurchasesParams): List<Purchase>

    /**
     * Resolves [params] to product details. Products that Play could not fetch (typo'd
     * IDs, geo-restricted, etc.) are silently dropped from the returned list — use
     * [queryProductDetailsWithUnfetched] to diagnose them.
     */
    @AnyThread
    public suspend fun queryProductDetails(params: QueryProductDetailsParams): List<ProductDetails>

    /**
     * Same as [queryProductDetails] but also exposes the list of products Play Billing
     * could not fetch. The Kotlin coroutine extension shipped by `billing-ktx` 8.x
     * returns the legacy `ProductDetailsResult`, which discards this information — use
     * this overload when you need diagnostics on missing products.
     */
    @AnyThread
    public suspend fun queryProductDetailsWithUnfetched(params: QueryProductDetailsParams): ProductDetailsQuery

    /**
     * Consumes a one-time consumable purchase, allowing the user to buy it again.
     * @return the consumed purchase token. Always present on a successful consume —
     *   PBL guarantees this. The library throws a [BillingException] subtype if the
     *   underlying call fails, so this method never returns under failure.
     */
    @AnyThread
    public suspend fun consumePurchase(params: ConsumeParams): String

    /**
     * Acknowledges a non-consumable purchase. Play requires acknowledgement within
     * 3 days of purchase or the transaction is auto-refunded.
     */
    @AnyThread
    public suspend fun acknowledgePurchase(params: AcknowledgePurchaseParams)

    /**
     * Convenience overload that acknowledges [purchase] only if it isn't already
     * acknowledged.
     *
     * Calling [acknowledgePurchase] on an already-acknowledged purchase produces
     * a [BillingException.DeveloperErrorException][com.kanetik.billing.exception.BillingException.DeveloperErrorException]
     * from Play. Google's integration guide explicitly recommends checking
     * [Purchase.isAcknowledged] first; this overload bakes that check in so
     * callers don't have to remember.
     *
     * Builds [AcknowledgePurchaseParams] from [purchase]'s purchase token and
     * delegates to the params-based [acknowledgePurchase]. No-ops silently if
     * [purchase] is already acknowledged.
     */
    @AnyThread
    public suspend fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        acknowledgePurchase(params)
    }

    /**
     * Launches the Play Billing purchase flow.
     *
     * Must be called on the main thread. The returned coroutine completes once Play
     * has shown the purchase UI; the actual purchase outcome arrives separately via
     * [BillingPurchaseUpdatesOwner.observePurchaseUpdates] (and may take seconds to
     * minutes — pending purchases can sit unresolved indefinitely).
     *
     * For one-time products, prefer [com.kanetik.billing.ext.toOneTimeFlowParams] to
     * build [params] correctly under PBL 8's offer-token rules. For higher-level
     * orchestration (in-flight guard, watchdog, typed result), see
     * [com.kanetik.billing.ext.PurchaseFlowCoordinator].
     */
    @MainThread
    public suspend fun launchFlow(activity: Activity, params: BillingFlowParams)
}
