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
 *  - [Revoked] — synthetic revocation event pushed by the consumer through
 *    [BillingRepository.emitExternalRevocation]. The library is transport-agnostic;
 *    consumers wiring up RTDN→Pub/Sub→FCM (or polling, or deeplinks) decode
 *    the signal and emit `(token, reason)` pairs through the new emit API.
 *    See [RevocationReason] for the recognized buckets.
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
 *
 * [Revoked] events are routed through the same `replay = 1` channel as
 * [Recovered] for the same reason: a revocation that arrives before the
 * consumer's collector attaches (the FCM listener decoded the RTDN payload
 * and called [BillingRepository.emitExternalRevocation] before the UI was
 * ready to observe) must not be lost. The same dedupe rule applies — a
 * re-attached collector receives the most recent event in the channel
 * again; gate by `purchaseToken` + variant if your handler isn't
 * idempotent.
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
     * **`purchases` may be empty.** The sweep emits a `Recovered` event on
     * *every* successful connection, including ones that find nothing — this
     * keeps the replay cache fresh so a subscriber that attaches after a
     * configuration change doesn't replay a stale recovery from a prior
     * session. Treat empty as a no-op (`forEach { handle(it) }` over an
     * empty list does nothing).
     *
     * **Dedupe by `purchaseToken` if you re-subscribe between sweeps.** Because
     * the recovery channel uses `replay = 1`, a subscriber that re-attaches
     * after successfully handling a recovered purchase will receive the same
     * `Purchase` snapshot again — the snapshot is from before your handle
     * call landed, so its `isAcknowledged` flag is still `false` and the
     * library's [com.kanetik.billing.BillingActions.acknowledgePurchase]`(Purchase)`
     * `isAcknowledged` short-circuit doesn't fire. Re-handling the snapshot
     * surfaces a [HandlePurchaseResult.Failure]:
     * `BillingException.DeveloperErrorException` for already-acknowledged
     * non-consumables, `ItemNotOwnedException` for already-consumed consumables.
     * Track handled tokens in a `Set<String>` to skip them deterministically;
     * persist via `SavedStateHandle` (or similar) if the dedupe needs to
     * survive process death.
     *
     * **Only mark tokens as handled on [HandlePurchaseResult.Success]**, not
     * on [HandlePurchaseResult.Failure]. A failure means the acknowledge /
     * consume didn't land — the next sweep needs to see the purchase again
     * to retry; suppressing the next replay would orphan it.
     *
     * Live `Success` events do not need this dedupe — they go through a
     * separate `replay = 0` channel.
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

    /**
     * A synthetic revocation event pushed into [observePurchaseUpdates]
     * [BillingPurchaseUpdatesOwner.observePurchaseUpdates] by a consumer via
     * [BillingRepository.emitExternalRevocation]. Carries the affected
     * `purchaseToken` and a [RevocationReason] bucket describing why the
     * entitlement was revoked.
     *
     * The library is transport-agnostic: it does **not** subscribe to FCM,
     * Real-Time Developer Notifications (RTDN), Pub/Sub, or any server-side
     * channel. Consumers driving server-side revocation are responsible for
     * decoding the transport (RTDN→Pub/Sub→FCM, polling, deeplinks, etc.)
     * into a `(purchaseToken, reason)` pair and pushing it through the emit
     * API. The single sealed-flow consumer in your app then handles
     * revocations alongside normal purchase outcomes, instead of maintaining
     * a parallel pipeline.
     *
     * Routed through the same `replay = 1` channel as [Recovered] so a
     * revocation arriving before a subscriber attaches isn't lost — see the
     * class-level "Replay semantics" notes for the full picture.
     *
     * **`purchases` is always empty.** Revocation arrives without a Play
     * [Purchase] object — the source is a server-side notification carrying
     * a token, not a re-issued PBL purchase callback. The empty list
     * satisfies the [PurchasesUpdate.purchases] contract; consumers should
     * branch on `purchaseToken` (and `reason`) directly.
     */
    public data class Revoked(
        val purchaseToken: String,
        val reason: RevocationReason,
    ) : PurchasesUpdate() {
        override val purchases: List<Purchase> = emptyList()
    }
}

/**
 * Why a purchase was revoked, as decoded by the consumer from the originating
 * transport (RTDN payload, server reconciliation result, support-tool action,
 * etc.). The library does not validate the mapping — the enum exists to give
 * downstream collectors a typed switch for differentiated UX (e.g. a
 * chargeback might warrant a security-flag entry while a refund warrants a
 * neutral "we've reversed your purchase" notice).
 */
public enum class RevocationReason {
    /**
     * Play refunded the purchase — either user-initiated (the user requested
     * a refund through Play Store / Google Pay) or merchant-initiated (your
     * support team issued a refund via the Play Console or Voided Purchases
     * API). Maps to RTDN `OneTimeProductNotification.type = ONE_TIME_PRODUCT_PURCHASE_REFUNDED`
     * and the equivalent `SubscriptionNotification.type = SUBSCRIPTION_REVOKED`
     * when the revocation source is a refund. Revoke entitlement; consider a
     * neutral confirmation toast.
     */
    Refunded,

    /**
     * A chargeback / payment dispute was resolved against the merchant — the
     * cardholder's bank reversed the charge. Distinct from [Refunded]
     * because chargebacks frequently warrant a security-policy response
     * (flag the account, revoke promotional credit, block re-purchase from
     * the same payment method) on top of plain entitlement revocation.
     * Consumers wiring their backend's chargeback webhook map to this value.
     */
    Chargeback,

    /**
     * A subscription was canceled and the grace period expired (Play stopped
     * billing renewals and the user no longer has active entitlement).
     * Subscription consumers receive this from RTDN `SubscriptionNotification`
     * `SUBSCRIPTION_EXPIRED` after `SUBSCRIPTION_CANCELED`. Included for
     * forward compatibility — the v0.1.x library does **not** emit this
     * itself (subscription helpers ship in v0.2.0); consumers running their
     * own subscription reconciliation can use this bucket today and the
     * v0.2.0 helpers will surface the same value automatically.
     */
    SubscriptionCanceled,

    /**
     * Other revocation source — consumer-supplied. Use when none of the
     * specific reasons fit (e.g. a support agent revoked entitlement
     * manually for a TOS violation, a fraud-detection system flagged the
     * purchase, a server-side reconciliation discovered the purchase no
     * longer exists in Play, etc.). The library does not interpret this
     * value beyond passing it through.
     */
    Other,
}
