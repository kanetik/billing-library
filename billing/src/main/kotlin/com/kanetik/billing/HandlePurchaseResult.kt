package com.kanetik.billing

import com.android.billingclient.api.Purchase
import com.kanetik.billing.exception.BillingException

/**
 * Outcome of [com.kanetik.billing.BillingActions.handlePurchase].
 *
 * Sealed and exhaustive — branching on it forces the caller to acknowledge
 * the failure case explicitly, which prevents the most common Play Billing
 * bug: granting entitlement before acknowledgement succeeds, then watching
 * Play auto-refund the unacknowledged purchase ~3 days later. That is, by
 * design, **the whole point of returning a typed result instead of throwing**:
 *
 * ```
 * when (val r = billing.handlePurchase(purchase, consume = false)) {
 *     HandlePurchaseResult.Success -> grantPremium()              // safe: ack landed
 *     is HandlePurchaseResult.AlreadyAcknowledged -> grantPremium() // safe: ack already in place
 *     HandlePurchaseResult.NotPurchased -> {}                     // pending — wait for terminal state
 *     is HandlePurchaseResult.Failure -> {
 *         // do NOT grant — auto-recovery sweep retries on next connect
 *         showError(r.exception.userFacingCategory)
 *     }
 * }
 * ```
 *
 * The four variants:
 *  - [Success] — the acknowledge / consume call landed. Safe to grant.
 *  - [AlreadyAcknowledged] — for `consume = false`, the library detected
 *    [Purchase.isAcknowledged] was already `true` and short-circuited
 *    before reaching out to Play. Safe to grant; useful to distinguish
 *    from [Success] for logging / telemetry (no PBL call was made).
 *  - [NotPurchased] — the purchase wasn't in
 *    [Purchase.PurchaseState.PURCHASED] state. Don't grant.
 *  - [Failure] — the acknowledge / consume call failed after the library's
 *    internal retry budget. Don't grant. Now unambiguously means a
 *    transient or terminal ack failure worth retrying — the previous
 *    overlap with `Failure(DeveloperErrorException)` for already-acked
 *    purchases is gone for *fresh* `Purchase` objects (the short-circuit
 *    inspects [Purchase.isAcknowledged] before reaching out to PBL). The
 *    stale-snapshot case is the one remaining caveat: a `Purchase`
 *    cached locally with `isAcknowledged = false` whose Play-side state
 *    has flipped to `true` (e.g., a `Recovered` snapshot that was already
 *    acked successfully but is being replayed) will still surface as
 *    `Failure(DeveloperErrorException)` on re-handle. The recovery sweep
 *    won't re-emit such a purchase as a fresh acknowledged object — it
 *    filters `PURCHASED && !isAcknowledged`, so once Play marks the
 *    purchase acknowledged it drops out of the sweep entirely. The stale
 *    snapshot persists in the recovery channel's replay slot until a
 *    later sweep emits a different result, or until the consumer queries
 *    fresh purchases via [com.android.billingclient.api.BillingClient.queryPurchasesAsync].
 *    (Issue #6 — Recovered dedupe — addresses this case directly by
 *    filtering replayed snapshots against handled tokens.)
 *
 * Lower-level [com.kanetik.billing.BillingActions.consumePurchase] and
 * [com.kanetik.billing.BillingActions.acknowledgePurchase] still throw
 * [BillingException] directly — callers at that layer are already in the
 * weeds and a thrown exception is appropriate. [handlePurchase] is the
 * high-level helper most apps use, which is why it gets the typed-result
 * treatment.
 */
public sealed class HandlePurchaseResult {

    /**
     * The acknowledge / consume call landed successfully. **Safe to grant
     * entitlement now.**
     */
    public data object Success : HandlePurchaseResult()

    /**
     * The purchase was already in the requested terminal state — no Play
     * Billing call was made. The library detected
     * [Purchase.isAcknowledged] was already `true` (for `consume = false`)
     * before reaching out to PBL.
     *
     * Treat this as a grant signal, identical to [Success] for entitlement
     * purposes. The variant exists separately so consumers can distinguish
     * "we just acked it" from "it was already done" for logging / metrics,
     * and so the previous `Failure(DeveloperErrorException)` recovery hole
     * goes away — [Failure] now unambiguously means a transient or
     * terminal ack failure that the consumer can choose to retry (or
     * untrack-on-Failure for the next recovery sweep).
     *
     * This variant is only produced for the non-consumable / `consume =
     * false` path. The `consume = true` path does not short-circuit on
     * [Purchase.isAcknowledged] — consumables aren't acknowledged, they
     * are consumed, and Play does not expose an `isConsumed` field on
     * [Purchase] for a parallel check.
     */
    public data class AlreadyAcknowledged(val purchase: Purchase) : HandlePurchaseResult()

    /**
     * The purchase wasn't in [Purchase.PurchaseState.PURCHASED] state. Two
     * cases produce this, with different consumer expectations:
     *
     *  - **`PENDING`** (the common case): asynchronous payment in flight —
     *    cash at convenience store, deferred billing, slow-network bank
     *    transfer. **Do not grant entitlement.** Play will fire a separate
     *    [com.kanetik.billing.OwnedPurchases.Live] update for the
     *    *same* purchase token when the payment confirms (or the purchase
     *    drops out entirely if it cancels). Wait for that.
     *
     *  - **`UNSPECIFIED_STATE`**: the purchase hit an undocumented or
     *    pre-PBL-8 state. **Do not grant entitlement, and don't expect a
     *    follow-up Success.** This typically indicates a malformed
     *    [Purchase] (e.g. from a custom `BillingActions` fake) or a PBL
     *    contract drift. Log and move on; the recovery sweep on the next
     *    successful connection will re-query owned purchases and surface
     *    anything actually pending.
     */
    public data object NotPurchased : HandlePurchaseResult()

    /**
     * The acknowledge / consume call failed after the library's internal
     * retry budget was exhausted. **Do not grant entitlement.**
     *
     * As of the [AlreadyAcknowledged] variant being added, [Failure] no
     * longer overlaps with the already-acked case **for fresh [Purchase]
     * objects** — consumers can safely untrack-on-Failure for retry on
     * the next recovery sweep without worrying that an already-acked
     * purchase will be re-tried forever via a
     * [BillingException.DeveloperErrorException]. The stale-snapshot
     * caveat above still applies: a locally-cached `Purchase` whose
     * Play-side `isAcknowledged` has flipped will still surface as
     * `Failure(DeveloperErrorException)` until the next sweep replaces
     * the snapshot.
     *
     * Recovery path depends on whether `recoverPurchasesOnConnect` is left
     * at its default (`true`):
     *  - **Default (`true`)**: the unacknowledged purchase is picked up by
     *    the auto-recovery sweep on the next successful Play Billing
     *    connection (see [com.kanetik.billing.OwnedPurchases.Recovered])
     *    and re-emitted to your collector. Re-call `handlePurchase` from
     *    your `Recovered` branch to retry.
     *  - **Opt-out (`recoverPurchasesOnConnect = false` on
     *    [com.kanetik.billing.BillingRepositoryCreator.create])**: the
     *    library will *not* re-emit the purchase. You're responsible for
     *    your own retry / reconciliation path — typically server-driven
     *    (validate against your backend; reconcile entitlement out of band).
     *
     * For UI: branch on `exception.userFacingCategory` to pick a localized
     * error message; never display `exception.message` (it's a debug dump).
     */
    public data class Failure(val exception: BillingException) : HandlePurchaseResult()
}
