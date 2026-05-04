package com.kanetik.billing

import kotlinx.coroutines.flow.Flow

/**
 * Stream of purchase events pushed by Play Billing — the coroutine-side analogue of
 * [com.android.billingclient.api.PurchasesUpdatedListener].
 *
 * Collect this flow in your premium / entitlement layer to react to every purchase
 * event. Branch on the [PurchaseEvent] sealed-interface roots:
 *  - [OwnedPurchases] (`Live`, `Recovered`) — owned-state events. Hand each
 *    purchase to [com.kanetik.billing.BillingActions.handlePurchase] and
 *    merge granted entitlement into your state. These are **incremental
 *    updates, not authoritative owned-state snapshots** (see [PurchaseEvent]
 *    KDoc and each variant's KDoc for the specific shape).
 *  - [FlowOutcome] (`Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`,
 *    `UnknownResponse`) — purchase-flow attempt outcomes; do **not** treat
 *    their `purchases` list as owned-state.
 *
 * Both one-time and subscription updates flow through the same stream — see
 * [PurchaseEvent] for the full state-machine guidance.
 *
 * Internally hot and shared via two underlying SharedFlows (live PBL events with
 * `replay = 0`, recovery-sweep events with `replay = 1`). Each subscription to
 * this flow subscribes to both channels: late subscribers see the most recent
 * recovery sweep (if any) plus all future emissions; live events do **not** replay
 * to re-attached subscribers (configuration changes, `repeatOnLifecycle`, etc.),
 * which avoids the "confetti fires twice on rotation" bug.
 *
 * Returned as [Flow] (not [kotlinx.coroutines.flow.SharedFlow]) because the type
 * can't express "replay-on-subscribe for some emissions but not others" — the
 * channel split provides that, and exposing a single SharedFlow at the top would
 * collapse the distinction.
 */
public interface BillingPurchaseUpdatesOwner {

    public fun observePurchaseUpdates(): Flow<PurchaseEvent>
}
