package com.kanetik.billing

import com.android.billingclient.api.Purchase
import com.kanetik.billing.exception.BillingException

/**
 * A single purchase event emitted by [BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *
 * # ⚠️ NEVER TREAT `event.purchases` FROM A [FlowOutcome] AS OWNED STATE
 *
 * `PurchaseEvent` is intentionally a marker interface with **no `purchases`
 * property**. Reading purchases requires narrowing to one of the two sealed
 * roots — [OwnedPurchases] or [FlowOutcome] — first. This split exists because
 * the two roots carry semantically different payloads, and treating them
 * uniformly is the most common silent footgun in entitlement code:
 *
 *  - **[OwnedPurchases]** — owned-state updates. Variants ([OwnedPurchases.Live],
 *    [OwnedPurchases.Recovered]) report purchases the user owns that need
 *    acknowledgement / consume / entitlement grant. Hand each to
 *    [com.kanetik.billing.BillingActions.handlePurchase] and merge into your
 *    own entitlement state — these events are **incremental updates, not
 *    authoritative owned-state snapshots** (see each variant's KDoc for the
 *    specific shape). For managed entitlement state, use
 *    `EntitlementCache` (when issue #3 lands).
 *  - **[FlowOutcome]** — purchase-flow attempt outcomes. Variants
 *    ([FlowOutcome.Pending], [FlowOutcome.Canceled],
 *    [FlowOutcome.ItemAlreadyOwned], [FlowOutcome.ItemUnavailable],
 *    [FlowOutcome.Failure], [FlowOutcome.UnknownResponse]) report what
 *    *happened* on a single launch attempt. The `purchases` lists are
 *    typically empty (or, for `Pending`, purchases that haven't completed
 *    yet) and **must not** be written to an entitlement cache — doing so
 *    silently corrupts state.
 *
 * The marker-interface design forces every consumer to narrow to a root before
 * reading purchases, which makes the FlowOutcome-isn't-owned-state rule a
 * compile-time concern rather than a runtime convention.
 *
 * ## Branch shape
 *
 * ```
 * billing.observePurchaseUpdates().collect { event ->
 *     when (event) {
 *         is OwnedPurchases.Live -> event.purchases.forEach { handleAndGrant(it) }
 *         is OwnedPurchases.Recovered -> event.purchases.forEach { handleAndGrant(it) }
 *         is FlowOutcome.Pending -> showPendingNotice() // do NOT grant
 *         is FlowOutcome.Canceled -> {}
 *         is FlowOutcome.ItemAlreadyOwned -> restoreEntitlement()
 *         is FlowOutcome.ItemUnavailable -> showSoldOut()
 *         is FlowOutcome.Failure -> showError(event.exception.userFacingCategory)
 *         is FlowOutcome.UnknownResponse -> reportFailure(event.code)
 *     }
 * }
 * ```
 *
 * Or, when the owned-state arms are identical:
 *
 * ```
 * when (event) {
 *     is OwnedPurchases -> event.purchases.forEach { handleAndGrant(it) }
 *     is FlowOutcome -> { /* surface UX per sub-variant */ }
 * }
 * ```
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
 * [FlowOutcome] events and [OwnedPurchases.Live] events **do not replay** to
 * re-attached subscribers — a `repeatOnLifecycle` collector that comes back
 * after a configuration change does not see the previous live event again.
 * This is the right semantic for purchase-flow outcomes and live owned-state
 * updates: the entitlement grant and any one-shot UX (confetti, toasts,
 * analytics) fired exactly once when the event arrived; replaying them on
 * rotation would be a bug.
 *
 * [OwnedPurchases.Recovered] events **do replay** to a late subscriber via a
 * separate recovery channel with `replay = 1`. The auto-sweep can fire on
 * connect before the consumer's collector attaches; the replay ensures the
 * recovered purchase isn't lost. Re-emission for the *same* sweep happens
 * only across re-subscriptions, not after a fresh sweep replaces it.
 */
public sealed interface PurchaseEvent

/**
 * Owned-state events: purchases the user owns that need acknowledgement /
 * consume / entitlement grant.
 *
 * **These are incremental updates, not authoritative owned-state snapshots.**
 * Specifically:
 *  - [Live] forwards whatever PBL delivers on its `OK` callback — that
 *    includes empty callbacks and `UNSPECIFIED_STATE` entries (see
 *    [Live]'s KDoc); it is not "everything the user owns right now."
 *  - [Recovered] carries only the `PURCHASED && !isAcknowledged` subset
 *    discovered by the auto-sweep — it is not the full owned set either.
 *
 * **Cache pattern: merge, do not replace.** Hand each event's purchases to
 * [com.kanetik.billing.BillingActions.handlePurchase] and merge granted
 * entitlement into your own state on `Success` (or `AlreadyAcknowledged`,
 * once issue #7 lands). Replacing your cache with `event.purchases` will
 * drop entitlement on every empty `Live` callback or every `Recovered`
 * sweep that doesn't see the full owned set. For managed entitlement
 * state, use `EntitlementCache` (when issue #3 lands), which handles the
 * merge logic and grace policy internally.
 *
 * Two variants, semantically identical for handling, distinct for UX:
 *  - [Live] — completed via the active purchase flow. Fire confetti / "thanks!"
 *    UX from this branch.
 *  - [Recovered] — discovered by the library's auto-sweep on connect.
 *    Background reconciliation; do not fire user-initiated UX.
 *
 * For each `PURCHASED`-state purchase: hand it to
 * [com.kanetik.billing.BillingActions.handlePurchase] (with `consume = true`
 * for consumables, `false` otherwise) to satisfy Play's acknowledgement
 * requirement. Read [Purchase.getQuantity] when granting consumable
 * entitlement (defaults to 1; multi-quantity purchases under-grant if
 * ignored).
 */
public sealed class OwnedPurchases : PurchaseEvent {
    public abstract val purchases: List<Purchase>

    /**
     * Live owned-purchases update with `BillingResponseCode == OK` —
     * typically a purchase that just completed via
     * [com.kanetik.billing.BillingActions.launchFlow], but also covers two
     * edge cases the underlying PBL listener delivers through this same path:
     *  - **`UNSPECIFIED_STATE` purchases**: rare, undocumented PBL state.
     *    `handlePurchase` no-ops on these (returns
     *    [com.kanetik.billing.HandlePurchaseResult.NotPurchased]).
     *  - **Empty OK callbacks** (`purchases.isEmpty()`): PBL occasionally
     *    fires the listener with no purchases; the listener forwards as
     *    `Live(emptyList())` so consumers don't have to special-case it.
     *
     * For each `PURCHASED`-state entry: hand it to
     * [com.kanetik.billing.BillingActions.handlePurchase] (with
     * `consume = true` for consumables, `false` otherwise) to satisfy
     * Play's acknowledgement requirement. For consumables, read
     * `purchase.quantity` when granting entitlement — see the
     * [PurchaseEvent] multi-quantity note.
     */
    public data class Live(override val purchases: List<Purchase>) : OwnedPurchases()

    /**
     * `PURCHASED && !isAcknowledged` purchases discovered by the library's
     * automatic sweep on each successful Play Billing connection.
     *
     * **Same handling as [Live]** — call
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
     * [Live] events do not need this dedupe — they go through a
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
    public data class Recovered(override val purchases: List<Purchase>) : OwnedPurchases()
}

/**
 * Purchase-flow attempt outcomes. These describe what *happened* on a single
 * launch attempt, not what the user owns. **Do not write `purchases` from a
 * [FlowOutcome] to your entitlement cache** — for [Pending] those purchases
 * haven't completed yet, and for the rest the list is typically empty.
 *
 * The variants:
 *  - [Pending] — purchases that completed at the protocol level but await
 *    confirmation. Wait for a matching [OwnedPurchases.Live] before granting.
 *  - [Canceled] — user dismissed the flow.
 *  - [ItemAlreadyOwned] — non-consumable already owned; treat as already-granted
 *    (restore entitlement from your own records).
 *  - [ItemUnavailable] — product not available (region, country, etc.).
 *  - [Failure] — Play returned a typed-failure response code (network error,
 *    billing-unavailable, service unavailable, etc.). Carries the matching
 *    [com.kanetik.billing.exception.BillingException] subtype so consumers can
 *    branch on `userFacingCategory` / `retryType` without re-deriving.
 *  - [UnknownResponse] — anything else (raw response code in [UnknownResponse.code]).
 */
public sealed class FlowOutcome : PurchaseEvent {
    public abstract val purchases: List<Purchase>

    /**
     * Purchases that completed at the protocol level but await confirmation
     * (cash payments, deferred billing, prepaid plans on PBL 7+).
     *
     * **Cardinal rule: do NOT acknowledge / consume / grant entitlement on a
     * Pending purchase.** The deferred payment may still fail. Wait for the
     * matching [OwnedPurchases.Live] update that arrives when the payment
     * confirms; act on entitlement then. Granting entitlement on Pending is
     * the most common bug in PBL integrations — even Google's own samples
     * have gotten this wrong.
     */
    public data class Pending(override val purchases: List<Purchase>) : FlowOutcome()

    /** User dismissed the purchase flow. `purchases` is typically empty. */
    public data class Canceled(override val purchases: List<Purchase>) : FlowOutcome()

    /**
     * Non-consumable already owned. Treat as already-granted: restore
     * entitlement from your own records rather than surfacing an error.
     * `purchases` is typically empty.
     */
    public data class ItemAlreadyOwned(override val purchases: List<Purchase>) : FlowOutcome()

    /**
     * Product not available for this user (region, country, configuration).
     * `purchases` is typically empty.
     */
    public data class ItemUnavailable(override val purchases: List<Purchase>) : FlowOutcome()

    /**
     * A purchase-flow callback that surfaced a typed [BillingException] subtype
     * Play Billing classifies as a failure (network errors, billing-unavailable,
     * service unavailable, etc.). Carries the original exception so consumers can
     * branch on subtype, [BillingException.userFacingCategory], or
     * [BillingException.retryType] without re-deriving from response codes.
     *
     * `purchases` is whatever Play returned in the failing callback — typically
     * empty, but preserved here for symmetry with the other variants.
     *
     * Library-internal entitlement helpers (e.g. `EntitlementCache` in
     * [com.kanetik.billing.entitlement]) consume this variant to drive
     * grace-window logic on transient outages. Most consumer code can treat
     * Failure the same way it would treat [UnknownResponse] — surface a
     * "couldn't reach Play, try again" message from [BillingException.userFacingCategory].
     */
    public data class Failure(
        public val exception: BillingException,
        override val purchases: List<Purchase>,
    ) : FlowOutcome()

    /**
     * Any response code outside the documented set above. Raw integer code
     * preserved in [code] for diagnostics; `purchases` is typically empty.
     */
    public data class UnknownResponse(
        val code: Int,
        override val purchases: List<Purchase>
    ) : FlowOutcome()
}
