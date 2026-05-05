package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.flow.MutableSharedFlow

internal class FlowPurchasesUpdatedListener(
    private val updateSubject: MutableSharedFlow<PurchaseEvent>,
    private val logger: BillingLogger = BillingLogger.Noop
) : PurchasesUpdatedListener {

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val safePurchases = purchases.orEmpty()
        val updates = computeUpdates(result, safePurchases)
        for (update in updates) {
            val emitted = updateSubject.tryEmit(update)
            if (!emitted) {
                // Should never happen given the 32-slot buffer in BillingClientStorage. If it
                // does, a slow collector dropped a real purchase update. Log only non-sensitive
                // identifiers — the full PurchaseEvent contains purchaseToken and signature,
                // which must not leak to logcat or Crashlytics.
                val productIds = safePurchases.flatMap { it.products }.distinct()
                logger.e(
                    "Purchase update dropped — buffer exhausted. " +
                        "responseCode=${result.responseCode} " +
                        "purchaseCount=${safePurchases.size} " +
                        "productIds=$productIds"
                )
            }
        }
    }

    private fun computeUpdates(result: BillingResult, purchases: List<Purchase>): List<PurchaseEvent> {
        return when (val responseCode = result.responseCode) {
            BillingResponseCode.OK -> {
                // Split by Purchase.purchaseState so consumers see Pending purchases as
                // a distinct sealed variant rather than buried inside Live. PBL
                // typically delivers one purchase per callback, but mixed batches are
                // possible and produce two emissions here (one OwnedPurchases.Live,
                // one FlowOutcome.Pending) — note the cross-root split: a single OK
                // callback can produce events on both PurchaseEvent roots. PBL also
                // occasionally fires OK with no purchases at all, which produces
                // zero emissions (handled below).
                val (pending, settled) = purchases.partition {
                    it.purchaseState == Purchase.PurchaseState.PENDING
                }
                if (settled.isEmpty() && pending.isEmpty()) {
                    // PBL fired the listener with literally nothing (settled + pending
                    // both empty). There's no actionable signal for the consumer —
                    // an empty OwnedPurchases.Live used to be forwarded here and
                    // silently wiped entitlement caches keyed off event.purchases,
                    // so we drop the event at the source. Symmetric with
                    // BillingClientStorage's empty-Recovered filter (same observable
                    // contract; mechanism differs — Live is dropped at construction,
                    // Recovered downstream of the sweep). Trace via logger so the
                    // breadcrumb is preserved for diagnostics — issue #13 specifically
                    // called for a library-internal log here rather than a consumer-
                    // facing event.
                    logger.d("PBL onPurchasesUpdated(OK) fired with no purchases — dropped (no actionable signal)")
                }
                buildList {
                    if (settled.isNotEmpty()) {
                        add(OwnedPurchases.Live(settled))
                    }
                    if (pending.isNotEmpty()) {
                        add(FlowOutcome.Pending(pending))
                    }
                }
            }
            BillingResponseCode.USER_CANCELED -> listOf(FlowOutcome.Canceled(purchases))
            BillingResponseCode.ITEM_ALREADY_OWNED -> listOf(FlowOutcome.ItemAlreadyOwned(purchases))
            BillingResponseCode.ITEM_UNAVAILABLE -> listOf(FlowOutcome.ItemUnavailable(purchases))
            BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingResponseCode.DEVELOPER_ERROR,
            BillingResponseCode.ERROR,
            BillingResponseCode.ITEM_NOT_OWNED ->
                // Map known PBL failure response codes to a typed Failure variant
                // carrying the matching BillingException subtype. Lets consumers
                // (and the entitlement-cache helper in com.kanetik.billing.entitlement)
                // branch on retry-hint / userFacingCategory without re-deriving
                // from raw response codes. UnknownResponse is reserved for codes
                // PBL doesn't document.
                listOf(FlowOutcome.Failure(BillingException.fromResult(result), purchases))
            else -> listOf(FlowOutcome.UnknownResponse(responseCode, purchases))
        }
    }
}
