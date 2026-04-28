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

/**
 * Retry strategy attached to a [com.kanetik.billing.exception.BillingException].
 *
 * The library applies these automatically inside its retry loop, but the field is
 * exposed on every [com.kanetik.billing.exception.BillingException] so callers can
 * decide whether to surface an error immediately or wait for the next attempt.
 *
 *  - [SIMPLE_RETRY] — a short fixed-delay retry; the error is typically transient
 *    (e.g. service disconnected; PBL 8's auto-reconnect needs a moment).
 *  - [EXPONENTIAL_RETRY] — back off and try again; the error is recoverable but
 *    may need network or service recovery time.
 *  - [REQUERY_PURCHASE_RETRY] — re-query owned purchases before retrying. Used for
 *    `ITEM_ALREADY_OWNED` / `ITEM_NOT_OWNED` mismatches caused by stale local state.
 *  - [NONE] — error is terminal; do not retry.
 */
public enum class RetryType {
    SIMPLE_RETRY,
    EXPONENTIAL_RETRY,
    REQUERY_PURCHASE_RETRY,
    NONE
}
