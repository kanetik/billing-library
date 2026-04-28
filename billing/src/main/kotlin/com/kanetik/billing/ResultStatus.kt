package com.kanetik.billing

/**
 * Coarse outcome category of a single Play Billing call.
 *
 * The library uses this internally to decide retry behavior; consumers will more
 * commonly branch on a typed [com.kanetik.billing.exception.BillingException]
 * instead, which carries the full [com.android.billingclient.api.BillingResult]
 * plus a [RetryType] hint.
 *
 * Note that Play Billing's pending-purchase concept (a Purchase whose
 * [com.android.billingclient.api.Purchase.purchaseState] is `PENDING`) is
 * surfaced via [com.kanetik.billing.PurchasesUpdate.Pending], not here —
 * pending is a property of an individual purchase, not a billing-call outcome.
 */
public enum class ResultStatus {
    SUCCESS,
    CANCELED,
    ERROR
}
