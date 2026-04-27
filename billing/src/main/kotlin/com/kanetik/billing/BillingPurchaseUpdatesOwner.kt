package com.kanetik.billing

import kotlinx.coroutines.flow.Flow

/**
 * Stream of purchase updates pushed by Play Billing — the coroutine-side analogue of
 * [com.android.billingclient.api.PurchasesUpdatedListener].
 *
 * Collect this flow in your premium / entitlement layer to react to every purchase
 * outcome (Success, Canceled, ItemAlreadyOwned, ItemUnavailable, UnknownResponse).
 * Both one-time and subscription updates flow through the same stream — branch on the
 * sealed [PurchasesUpdate] subtype.
 *
 * The flow is conflated with a generous buffer (32 slots) so a slow collector can't
 * drop a real purchase update. Collection survives across [BillingConnector]
 * disconnects/reconnects.
 */
public interface BillingPurchaseUpdatesOwner {

    public fun observePurchaseUpdates(): Flow<PurchasesUpdate>
}
