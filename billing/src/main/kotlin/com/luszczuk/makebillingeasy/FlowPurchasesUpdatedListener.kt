package com.luszczuk.makebillingeasy

import android.util.Log
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.flow.MutableSharedFlow

class FlowPurchasesUpdatedListener(
    private val updateSubject: MutableSharedFlow<PurchasesUpdate>
) : PurchasesUpdatedListener {

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val safePurchases = purchases.orEmpty()
        val emitted = updateSubject.tryEmit(convertToUpdate(result, safePurchases))
        if (!emitted) {
            // Should never happen given the 32-slot buffer in BillingClientStorage. If it
            // does, a slow collector dropped a real purchase update. Log only non-sensitive
            // identifiers — the full PurchasesUpdate contains purchaseToken and signature,
            // which must not leak to logcat or Crashlytics.
            val productIds = safePurchases.flatMap { it.products }.distinct()
            Log.e(
                "MakeBillingEasy",
                "Purchase update dropped — buffer exhausted. " +
                    "responseCode=${result.responseCode} " +
                    "purchaseCount=${safePurchases.size} " +
                    "productIds=$productIds"
            )
        }
    }

    private fun convertToUpdate(result: BillingResult, purchases: List<Purchase>): PurchasesUpdate =
        when (val responseCode = result.responseCode) {
            BillingResponseCode.OK -> PurchasesUpdate.Success(purchases)
            BillingResponseCode.USER_CANCELED -> PurchasesUpdate.Canceled(purchases)
            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchasesUpdate.ItemAlreadyOwned(purchases)
            BillingResponseCode.ITEM_UNAVAILABLE -> PurchasesUpdate.ItemUnavailable(purchases)
            else -> PurchasesUpdate.UnknownResponse(responseCode, purchases)
        }
}