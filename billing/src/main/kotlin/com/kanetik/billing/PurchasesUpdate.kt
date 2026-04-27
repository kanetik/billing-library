package com.kanetik.billing

import com.android.billingclient.api.Purchase

/**
 * A single purchase update emitted by [BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *
 * Each variant carries the purchases list Play returned with the update (often empty
 * for non-success outcomes). Branch on the sealed subtype to react to each terminal
 * state of a purchase flow:
 *
 *  - [Success] — purchases completed; acknowledge / consume them per Play's rules.
 *  - [Canceled] — user dismissed the flow.
 *  - [ItemAlreadyOwned] — non-consumable already owned; treat as already-granted.
 *  - [ItemUnavailable] — product not available (region, country, etc.).
 *  - [UnknownResponse] — anything else (raw response code in [UnknownResponse.code]).
 */
public sealed class PurchasesUpdate {
    public abstract val purchases: List<Purchase>

    public data class Success(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class ItemAlreadyOwned(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class ItemUnavailable(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class Canceled(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class UnknownResponse(
        val code: Int,
        override val purchases: List<Purchase>
    ) : PurchasesUpdate()
}
