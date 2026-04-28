package com.kanetik.billing

import com.android.billingclient.api.Purchase

/**
 * A single purchase update emitted by [BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *
 * Each variant carries the purchases list Play returned with the update (often empty
 * for non-success outcomes). Branch on the sealed subtype to react to each outcome:
 *
 *  - [Success] — purchases completed; acknowledge / consume them per Play's rules.
 *  - [Pending] — purchases that completed at the protocol level but await
 *    confirmation (cash payments, deferred billing). **Do not grant entitlement
 *    yet** — wait for a matching [Success] later. See PBL's pending-purchases
 *    guidance for the full state-machine rules.
 *  - [Canceled] — user dismissed the flow.
 *  - [ItemAlreadyOwned] — non-consumable already owned; treat as already-granted.
 *  - [ItemUnavailable] — product not available (region, country, etc.).
 *  - [UnknownResponse] — anything else (raw response code in [UnknownResponse.code]).
 *
 * When a single Play callback contains both pending and settled purchases (rare —
 * Play typically delivers one per callback), the listener emits both [Success] and
 * [Pending] separately so consumers can react to each.
 */
public sealed class PurchasesUpdate {
    public abstract val purchases: List<Purchase>

    public data class Success(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class Pending(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class ItemAlreadyOwned(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class ItemUnavailable(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class Canceled(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class UnknownResponse(
        val code: Int,
        override val purchases: List<Purchase>
    ) : PurchasesUpdate()
}
