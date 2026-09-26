package com.kanetik.billing

/**
 * Retry classification attached to a [com.kanetik.billing.exception.BillingException],
 * exposed so callers can decide whether to surface an error immediately or retry.
 *
 *  - [SIMPLE_RETRY] — the error is typically transient (e.g. service disconnected).
 *  - [EXPONENTIAL_RETRY] — the error is recoverable but may need network or
 *    service recovery time.
 *  - [NONE] — error is terminal; do not retry.
 */
public enum class RetryType {
    SIMPLE_RETRY,
    EXPONENTIAL_RETRY,
    NONE
}
