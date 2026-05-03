package com.kanetik.billing

import com.android.billingclient.api.Purchase

/**
 * A single purchase update emitted by [BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *
 * Each variant carries the purchases list Play returned with the update (often empty
 * for non-success outcomes). Branch on the sealed subtype to react to each outcome:
 *
 *  - [Success] — purchases completed via the active purchase flow; acknowledge /
 *    consume them per Play's rules.
 *  - [Recovered] — `PURCHASED && !isAcknowledged` purchases discovered by the
 *    library's automatic sweep on each successful connection. Same handling as
 *    [Success] (acknowledge / consume), distinct variant so UX (confetti, "thanks!"
 *    toasts) can differentiate user-initiated purchases from background recovery.
 *    See [BillingRepositoryCreator.create][com.kanetik.billing.BillingRepositoryCreator.create]'s
 *    `recoverPurchasesOnConnect` parameter to disable.
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
 *
 * ## Granting entitlement (multi-quantity)
 *
 * For consumables, always grant `purchase.quantity` units, not 1. Play supports
 * multi-quantity purchases (the Play Console flag must be enabled, and you can cap
 * via `BillingFlowParams`). The field defaults to 1 so single-unit code keeps
 * working — but ignoring it on a multi-quantity purchase silently under-grants.
 *
 * ## Replay semantics
 *
 * Live events ([Success], [Pending], [Canceled], [ItemAlreadyOwned],
 * [ItemUnavailable], [UnknownResponse]) **do not replay** to re-attached
 * subscribers — a `repeatOnLifecycle` collector that comes back after a
 * configuration change does not see the previous live event again. This is
 * the right semantic for purchase-flow outcomes: the entitlement grant and
 * any one-shot UX (confetti, toasts, analytics) fired exactly once when the
 * event arrived; replaying them on rotation would be a bug.
 *
 * [Recovered] events **do replay** to a late subscriber via a separate
 * recovery channel with `replay = 1`. The auto-sweep can fire on connect
 * before the consumer's collector attaches; the replay ensures the
 * recovered purchase isn't lost. Re-emission for the *same* sweep happens
 * only across re-subscriptions, not after a fresh sweep replaces it.
 */
public sealed class PurchasesUpdate {
    public abstract val purchases: List<Purchase>

    /**
     * Purchases that just completed via an active [com.kanetik.billing.BillingActions.launchFlow]
     * call. Hand each one to [com.kanetik.billing.BillingActions.handlePurchase] (with
     * `consume = true` for consumables, `false` otherwise) to satisfy Play's
     * acknowledgement requirement.
     *
     * For consumables, read `purchase.quantity` when granting entitlement —
     * see the class-level multi-quantity note.
     */
    public data class Success(override val purchases: List<Purchase>) : PurchasesUpdate()

    /**
     * `PURCHASED && !isAcknowledged` purchases discovered by the library's
     * automatic sweep on each successful Play Billing connection.
     *
     * **Same handling as [Success]** — call
     * [com.kanetik.billing.BillingActions.handlePurchase] (with `consume = true`
     * for consumables, `false` otherwise) to acknowledge / consume them and
     * grant entitlement.
     *
     * The variant exists so consumer UX can distinguish background recovery
     * from user-initiated purchases (don't fire confetti for recovered
     * purchases — the user didn't just tap Buy). The handle-and-grant code is
     * identical; only the surrounding UX differs.
     *
     * **Why recovery matters:** Play auto-refunds purchases that aren't
     * acknowledged within 3 days. App crashes, network failures, or force-quits
     * mid-acknowledge leave purchases stranded — without a recovery sweep on
     * the next launch, the user paid and gets refunded with no entitlement.
     *
     * Disabled via [com.kanetik.billing.BillingRepositoryCreator.create]'s
     * `recoverPurchasesOnConnect = false` parameter (default is `true`).
     */
    public data class Recovered(override val purchases: List<Purchase>) : PurchasesUpdate()

    /**
     * Purchases that completed at the protocol level but await confirmation
     * (cash payments, deferred billing, prepaid plans on PBL 7+).
     *
     * **Cardinal rule: do NOT acknowledge / consume / grant entitlement on a
     * Pending purchase.** The deferred payment may still fail. Wait for the
     * matching [Success] update that arrives when the payment confirms; act
     * on entitlement then. Granting entitlement on Pending is the most
     * common bug in PBL integrations — even Google's own samples have gotten
     * this wrong.
     */
    public data class Pending(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class ItemAlreadyOwned(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class ItemUnavailable(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class Canceled(override val purchases: List<Purchase>) : PurchasesUpdate()
    public data class UnknownResponse(
        val code: Int,
        override val purchases: List<Purchase>
    ) : PurchasesUpdate()
}
