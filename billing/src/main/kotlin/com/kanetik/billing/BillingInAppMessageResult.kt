package com.kanetik.billing

/**
 * Outcome of [BillingActions.showInAppMessages].
 *
 * Wraps PBL's `InAppMessageResult` so the library's public API doesn't pin its
 * ABI to PBL's holder-class shape (consistent with [BillingConnectionResult]
 * and [ProductDetailsQuery]).
 */
public sealed class BillingInAppMessageResult {

    /**
     * Play returned no actionable in-app message — either there was nothing to
     * show, or Play decided no action was needed (e.g. the user is already
     * up-to-date on the relevant subscription state). Treat as a no-op.
     */
    public data object NoActionNeeded : BillingInAppMessageResult()

    /**
     * The user took an action via the in-app message that updated a subscription
     * (commonly: fixed a payment method that was failing). The affected
     * subscription's [purchaseToken] is included so the caller can re-query
     * purchases / refresh entitlement state.
     */
    public data class SubscriptionStatusUpdated(public val purchaseToken: String) :
        BillingInAppMessageResult()
}
