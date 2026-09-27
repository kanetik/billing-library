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
    private val logger: BillingLogger = BillingLogger.Noop,
    private val launchFailureSuppression: LaunchFailureSuppression = LaunchFailureSuppression()
) : PurchasesUpdatedListener {

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (launchFailureSuppression.consume(result.responseCode)) {
            logger.d(
                "onPurchasesUpdated: suppressed echo of the synchronous launch failure - " +
                    BillingLoggingUtils.createDetailedBillingContext(result, operationContext = "onPurchasesUpdated")
            )
            return
        }
        val safePurchases = purchases.orEmpty()
        logger.d(
            "onPurchasesUpdated: " +
                BillingLoggingUtils.createDetailedBillingContext(result, operationContext = "onPurchasesUpdated")
        )
        val updates = computeUpdates(result, safePurchases)
        for (update in updates) {
            val hadSubscriber = updateSubject.subscriptionCount.value > 0
            val emitted = updateSubject.tryEmit(update)
            if (!emitted || !hadSubscriber) {
                // Only non-sensitive identifiers here — the full PurchaseEvent
                // contains purchaseToken and signature, which must not leak
                // to logcat or Crashlytics.
                val productIds = safePurchases.flatMap { it.products }.distinct()
                val context = "responseCode=${result.responseCode} " +
                    "purchaseCount=${safePurchases.size} " +
                    "productIds=$productIds"
                if (!emitted) {
                    // Should never happen given the 32-slot buffer in BillingClientStorage —
                    // a slow collector dropped a real purchase update.
                    logger.e("Purchase update dropped — buffer exhausted. $context")
                } else {
                    logger.w("Purchase update emitted with no active observePurchaseUpdates() collector — $context")
                }
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
                // PBL sometimes fires the listener with settled + pending both empty.
                // There's no actionable signal for the consumer — an empty
                // OwnedPurchases.Live used to be forwarded here and silently wiped
                // entitlement caches keyed off event.purchases, so we drop the event
                // at the source. Symmetric with BillingClientStorage's empty-Recovered
                // filter (same observable contract; mechanism differs — Live is
                // dropped at construction, Recovered downstream of the sweep).
                buildList {
                    if (settled.isNotEmpty()) {
                        add(OwnedPurchases.Live(settled))
                    }
                    if (pending.isNotEmpty()) {
                        add(FlowOutcome.Pending(pending, result))
                    }
                }
            }
            BillingResponseCode.USER_CANCELED -> listOf(FlowOutcome.Canceled(purchases, result))
            BillingResponseCode.ITEM_ALREADY_OWNED -> listOf(FlowOutcome.ItemAlreadyOwned(purchases, result))
            BillingResponseCode.ITEM_UNAVAILABLE -> listOf(FlowOutcome.ItemUnavailable(purchases, result))
            BillingResponseCode.BILLING_UNAVAILABLE ->
                // Here (mid-flow) Play has already shown the user feedback about
                // why the purchase didn't go through (declined payment, outdated
                // Play Store, unsupported country, admin-disabled purchases, or
                // an OEM-blocked Play Store) — kept out of Failure so it never
                // carries BillingErrorCategory.BillingUnavailable.
                listOf(FlowOutcome.UserBillingError(purchases, result))
            BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.SERVICE_UNAVAILABLE,
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
                listOf(FlowOutcome.Failure(BillingException.fromResult(result), purchases, result))
            else -> listOf(FlowOutcome.UnknownResponse(responseCode, purchases, result))
        }
    }
}
