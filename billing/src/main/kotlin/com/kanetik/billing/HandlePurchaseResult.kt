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
     * The purchase wasn't in [Purchase.PurchaseState.PURCHASED] state — most
     * commonly it's PENDING (cash payment, deferred billing) and we're waiting
     * for the payment to confirm. **Do not grant entitlement.** The matching
     * [com.kanetik.billing.PurchasesUpdate.Success] arrives when the payment
     * confirms; act on entitlement then.
     */
    public data object NotPurchased : HandlePurchaseResult()

    /**
     * The acknowledge / consume call failed after the library's internal
     * retry budget was exhausted. **Do not grant entitlement** — the
     * unacknowledged purchase will be picked up by the auto-recovery sweep
     * on the next successful Play Billing connection (see
     * [com.kanetik.billing.PurchasesUpdate.Recovered]) and retried then.
     *
     * For UI: branch on `exception.userFacingCategory` to pick a localized
     * error message; never display `exception.message` (it's a debug dump).
     */
    public data class Failure(val exception: BillingException) : HandlePurchaseResult()
}
