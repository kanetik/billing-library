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
 *     HandlePurchaseResult.Success -> grantEntitlement()           // safe: ack landed
 *     HandlePurchaseResult.AlreadyAcknowledged -> grantEntitlement() // safe: ack already in place
 *     HandlePurchaseResult.NotPurchased -> {}                     // pending — wait for terminal state
 *     HandlePurchaseResult.NotOwned -> {}                          // stale snapshot — defer to grace/revoke
 *     is HandlePurchaseResult.Failure -> {
 *         // do NOT grant — it comes back as OwnedPurchases.Recovered; handle it there
 *         showError(r.exception.userFacingCategory)
 *     }
 * }
 * ```
 *
 * The five variants:
 *  - [Success] — the acknowledge / consume call landed. Safe to grant.
 *  - [AlreadyAcknowledged] — for `consume = false`, the library detected
 *    the purchase was already acknowledged and short-circuited
 *    before reaching out to Play. Safe to grant; useful to distinguish
 *    from [Success] for logging / telemetry (no PBL call was made).
 *  - [NotPurchased] — the purchase wasn't in
 *    [Purchase.PurchaseState.PURCHASED] state. Don't grant.
 *  - [NotOwned] — Play replied `ITEM_NOT_OWNED` from acknowledge / consume:
 *    ownership disagrees with the input. The caller passed in a stale
 *    `Purchase` (typically from a cached `queryPurchases` snapshot) whose
 *    Play-side ownership has since flipped. Don't grant; defer to your
 *    grace / revoke logic and consider re-querying owned purchases.
 *  - [Failure] — the acknowledge / consume call failed after the library's
 *    internal retry budget. Don't grant. Now unambiguously means a
 *    transient or terminal ack failure worth retrying — the previous
 *    overlap with `Failure(DeveloperErrorException)` for already-acked
 *    purchases is gone for *fresh* `Purchase` objects (the short-circuit
 *    inspects [Purchase.isAcknowledged] before reaching out to PBL), and
 *    the previous overlap with `Failure(ItemNotOwnedException)` for
 *    ownership-mismatch cases is gone — that case now surfaces as
 *    [NotOwned] instead. The repository from
 *    [com.kanetik.billing.BillingRepositoryCreator.create] also returns
 *    [AlreadyAcknowledged] for a token it has itself acknowledged or
 *    consumed in this process, even from a stale `Purchase` copy with
 *    `isAcknowledged = false`. The one remaining caveat is a stale copy of
 *    a purchase acknowledged outside that repository instance (another
 *    repository instance, another process, or a server): re-handling it
 *    still surfaces `Failure(DeveloperErrorException)`.
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
     * Billing call was made. For `consume = false`, the library detected
     * before reaching out to PBL that [Purchase.isAcknowledged] was already
     * `true`, or that the repository from
     * [com.kanetik.billing.BillingRepositoryCreator.create] had already
     * acknowledged or consumed this token in this process.
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
    public data object AlreadyAcknowledged : HandlePurchaseResult()

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
     * Play reported the purchase isn't owned anymore — the underlying
     * acknowledge / consume call returned
     * [com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_NOT_OWNED],
     * which the library treats as terminal (no retry). **Do not grant entitlement.**
     *
     * Typical cause: the consumer passed in a stale [Purchase] from a
     * cached [com.android.billingclient.api.BillingClient.queryPurchasesAsync]
     * snapshot, the library went to ack / consume it, and Play replied
     * "this purchase isn't owned anymore." Ownership disagrees with the
     * input — refunded, revoked, or already consumed by a parallel
     * client.
     *
     * Contrast with the neighboring variants:
     *  - [NotPurchased] is a **pre-flight** state check ("the [Purchase]
     *    object's `purchaseState` isn't `PURCHASED` — wait for terminal
     *    state, no PBL call was made"). [NotOwned] is a **post-flight**
     *    response from Play ("we tried to ack / consume; Play says this
     *    purchase doesn't exist anymore").
     *  - [Failure] covers transient or terminal failures of the **ack
     *    call itself** (network, service disconnected, etc.) — ownership
     *    state is unchanged and the library re-emits the purchase as
     *    [com.kanetik.billing.OwnedPurchases.Recovered] so it can be
     *    handled again. [NotOwned] is
     *    the opposite: the ack didn't
     *    fail, ownership did. Re-trying the ack against a non-owned
     *    purchase will keep returning [NotOwned].
     *
     * Recommended consumer treatment: don't grant; defer to your grace
     * window / revoke logic; consider re-querying owned purchases via
     * [BillingActions.queryPurchases][com.kanetik.billing.BillingActions.queryPurchases]
     * to get a fresh snapshot before deciding whether to revoke
     * entitlement.
     */
    public data object NotOwned : HandlePurchaseResult()

    /**
     * The acknowledge / consume call failed after the library's internal
     * retry budget was exhausted. **Do not grant entitlement.**
     *
     * As of the [AlreadyAcknowledged] variant being added, [Failure] no
     * longer overlaps with the already-acked case **for fresh [Purchase]
     * objects** — consumers can safely untrack-on-Failure for retry on
     * the next recovery sweep without worrying that an already-acked
     * purchase will be re-tried forever via a
     * [BillingException.DeveloperErrorException]. The stale-copy caveat
     * above still applies to a purchase acknowledged outside this
     * repository instance.
     *
     * As of the [NotOwned] variant being added, [Failure] also no longer
     * carries [BillingException.ItemNotOwnedException] — that case
     * surfaces as [NotOwned] instead, so [Failure] now unambiguously
     * means a transient or terminal **ack-call** failure (network,
     * service disconnected, fatal billing error, etc.) where ownership
     * state is unchanged and retry is the right call. Ownership-mismatch
     * — where retry can't help — has its own variant.
     *
     * The library runs one extra recovery sweep shortly after a `Failure`
     * here, at most three times in a row for the same purchase, regardless
     * of `recoverPurchasesOnConnect`, and that sweep
     * re-emits the still-unacknowledged purchase as
     * [com.kanetik.billing.OwnedPurchases.Recovered]. The library does not
     * re-issue the acknowledge / consume itself: re-call `handlePurchase`
     * from your `Recovered` branch to retry. Recovery beyond that depends on
     * whether `recoverPurchasesOnConnect` is left at its default (`true`):
     *  - **Default (`true`)**: the unacknowledged purchase is also picked
     *    up by the auto-recovery sweep on every successful Play Billing
     *    connection (see [com.kanetik.billing.OwnedPurchases.Recovered])
     *    and re-emitted to your collector. Re-call `handlePurchase` from
     *    your `Recovered` branch to retry.
     *  - **Opt-out (`recoverPurchasesOnConnect = false` on
     *    [com.kanetik.billing.BillingRepositoryCreator.create])**: only the
     *    in-session re-emission applies (at most three times in a row for
     *    the same purchase), so you still need a `Recovered` branch; the library will *not* re-emit the purchase on a fresh
     *    connect. You're responsible for your own
     *    retry / reconciliation path beyond that — typically server-driven
     *    (validate against your backend; reconcile entitlement out of band).
     *
     * For UI: branch on `exception.userFacingCategory` to pick a localized
     * error message; never display `exception.message` (it's a debug dump).
     */
    public data class Failure(val exception: BillingException) : HandlePurchaseResult()
}
