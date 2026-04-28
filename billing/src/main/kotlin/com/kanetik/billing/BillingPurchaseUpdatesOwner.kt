package com.kanetik.billing

import kotlinx.coroutines.flow.SharedFlow

/**
 * Stream of purchase updates pushed by Play Billing — the coroutine-side analogue of
 * [com.android.billingclient.api.PurchasesUpdatedListener].
 *
 * Collect this flow in your premium / entitlement layer to react to every purchase
 * outcome (Success, Canceled, ItemAlreadyOwned, ItemUnavailable, UnknownResponse).
 * Both one-time and subscription updates flow through the same stream — branch on the
 * sealed [PurchasesUpdate] subtype.
 *
 * Returned as [SharedFlow] to communicate that this is a hot, shared stream — every
 * collector sees the same emissions, and there's a generous 32-slot buffer so a slow
 * collector can't drop a real purchase update. Collection survives across
 * [BillingConnector] disconnects/reconnects.
 */
public interface BillingPurchaseUpdatesOwner {

    public fun observePurchaseUpdates(): SharedFlow<PurchasesUpdate>
}
