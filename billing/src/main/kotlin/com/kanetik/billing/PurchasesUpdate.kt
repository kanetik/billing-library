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
 * recovered purchase isn't lost. The library tracks acknowledged tokens
 * internally and filters them out of the replay, so a re-attached
 * subscriber that has already handled the recovered purchase does not
 * see a stale snapshot for it again — see the [Recovered] KDoc.
 */
public sealed class PurchasesUpdate {
    public abstract val purchases: List<Purchase>

    /**
     * Live purchase update with `BillingResponseCode == OK` — typically a
     * purchase that just completed via [com.kanetik.billing.BillingActions.launchFlow],
     * but also covers two edge cases the underlying PBL listener delivers
     * through this same path:
     *  - **`UNSPECIFIED_STATE` purchases**: rare, undocumented PBL state.
     *    `handlePurchase` no-ops on these (returns
     *    [com.kanetik.billing.HandlePurchaseResult.NotPurchased]).
     *  - **Empty OK callbacks** (`purchases.isEmpty()`): PBL occasionally
     *    fires the listener with no purchases; the listener forwards as
     *    `Success(emptyList())` so consumers don't have to special-case it.
     *
     * For each `PURCHASED`-state entry: hand it to
     * [com.kanetik.billing.BillingActions.handlePurchase] (with
     * `consume = true` for consumables, `false` otherwise) to satisfy
     * Play's acknowledgement requirement. For consumables, read
     * `purchase.quantity` when granting entitlement — see the class-level
     * multi-quantity note.
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
     * identical for one-time products; only the surrounding UX differs.
     *
     * **⚠️ Subscription replacements need special handling (until v0.2.0).**
     * Subscription upgrade/downgrade/crossgrade purchases carry a non-null
     * `linkedPurchaseToken` pointing at the prior subscription. Treating them
     * as fresh grants double-grants entitlement on plan changes. Until v0.2.0
     * ships the typed `SubscriptionReplacement` variant (see `docs/ROADMAP.md`),
     * consumers handling subscriptions need to parse `purchase.originalJson`
     * for the `linkedPurchaseToken` field — PBL's [Purchase] API doesn't
     * expose a getter for it (`Purchase.AccountIdentifiers` only carries
     * `obfuscatedAccountId` / `obfuscatedProfileId`):
     *
     * ```
     * fun Purchase.linkedPurchaseToken(): String? = try {
     *     org.json.JSONObject(originalJson)
     *         .optString("linkedPurchaseToken")
     *         .takeIf { it.isNotEmpty() }
     * } catch (e: org.json.JSONException) { null }
     * ```
     *
     * Treat a non-null result as a plan change rather than a fresh purchase
     * (invalidate the old token, grant against the new one). One-time
     * products never carry a `linkedPurchaseToken`, so IAP-only apps are
     * unaffected.
     *
     * **The library tracks acknowledged tokens internally and suppresses
     * replay of `Recovered` for already-handled purchases.** Both the live
     * sweep result and the `replay = 1` re-emission to a late subscriber are
     * filtered against tokens passed through
     * [com.kanetik.billing.BillingActions.acknowledgePurchase] /
     * [com.kanetik.billing.BillingActions.consumePurchase] (including via
     * [com.kanetik.billing.BillingActions.handlePurchase]) earlier in this
     * billing-connection lifetime. If every purchase in a sweep is already
     * acknowledged, no event is emitted at all (rather than `Recovered(emptyList())`),
     * so consumers no longer need to maintain their own `Set<String>` dedupe
     * to suppress stale replays. Tokens are intentionally not persisted
     * across a full connection-share teardown (60s of zero subscribers) —
     * a fresh sweep on reconnect re-queries Play and surfaces only genuinely
     * unacked purchases.
     *
     * Idempotent handling is still a good idea if you trigger one-shot UX off
     * `Recovered` (badge animations, analytics events, etc.) — but the
     * dedupe `Set<String>` consumers used to need is no longer required.
     *
     * Live `Success` events go through a separate `replay = 0` channel and
     * don't replay at all.
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
