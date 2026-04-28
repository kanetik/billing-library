package com.kanetik.billing

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
