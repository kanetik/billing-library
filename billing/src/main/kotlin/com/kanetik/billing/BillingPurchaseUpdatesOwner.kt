package com.kanetik.billing

import kotlinx.coroutines.flow.Flow

/**
 * Stream of purchase updates pushed by Play Billing — the coroutine-side analogue of
 * [com.android.billingclient.api.PurchasesUpdatedListener].
 *
 * Collect this flow in your premium / entitlement layer to react to every purchase
 * outcome (Success, Recovered, Pending, Canceled, ItemAlreadyOwned, ItemUnavailable,
 * UnknownResponse). Both one-time and subscription updates flow through the same
 * stream — branch on the sealed [PurchasesUpdate] subtype.
 *
 * Internally hot and shared via two underlying SharedFlows (live PBL events with
 * `replay = 0`, recovery-sweep events with `replay = 1`). Each subscription to
 * this flow subscribes to both channels: late subscribers see the most recent
 * recovery sweep (if any) plus all future emissions; live events do **not** replay
 * to re-attached subscribers (configuration changes, `repeatOnLifecycle`, etc.),
 * which avoids the "confetti fires twice on rotation" bug. See [PurchasesUpdate]
 * for the full state-machine guidance.
 *
 * Returned as [Flow] (not [kotlinx.coroutines.flow.SharedFlow]) because the type
 * can't express "replay-on-subscribe for some emissions but not others" — the
 * channel split provides that, and exposing a single SharedFlow at the top would
 * collapse the distinction.
 */
public interface BillingPurchaseUpdatesOwner {

    public fun observePurchaseUpdates(): Flow<PurchasesUpdate>
}
