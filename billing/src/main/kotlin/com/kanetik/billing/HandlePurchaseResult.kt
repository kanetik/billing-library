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
 *     HandlePurchaseResult.Success -> grantPremium()  // safe: ack landed
 *     HandlePurchaseResult.NotPurchased -> {}         // pending — wait for terminal state
 *     is HandlePurchaseResult.Failure -> {
 *         // do NOT grant — auto-recovery sweep retries on next connect
 *         showError(r.exception.userFacingCategory)
 *     }
 * }
 * ```
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
     * The purchase wasn't in [Purchase.PurchaseState.PURCHASED] state. Two
     * cases produce this, with different consumer expectations:
     *
     *  - **`PENDING`** (the common case): asynchronous payment in flight —
     *    cash at convenience store, deferred billing, slow-network bank
     *    transfer. **Do not grant entitlement.** Play will fire a separate
     *    [com.kanetik.billing.PurchasesUpdate.Success] update for the
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
     * Recovery path depends on whether `recoverPurchasesOnConnect` is left
     * at its default (`true`):
     *  - **Default (`true`)**: the unacknowledged purchase is picked up by
     *    the auto-recovery sweep on the next successful Play Billing
     *    connection (see [com.kanetik.billing.PurchasesUpdate.Recovered])
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
