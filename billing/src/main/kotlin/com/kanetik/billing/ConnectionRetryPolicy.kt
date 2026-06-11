package com.kanetik.billing

/**
 * Bounded retry policy applied to Play Billing **connection-setup** failures
 * that the library classifies as transient — i.e. those whose
 * [com.kanetik.billing.exception.BillingException.retryType] is
 * [RetryType.SIMPLE_RETRY] or [RetryType.EXPONENTIAL_RETRY].
 *
 * ## Why this exists
 *
 * `BillingClient.startConnection` can report a transient failure to
 * `onBillingSetupFinished` — most commonly `SERVICE_DISCONNECTED`
 * (classified [RetryType.SIMPLE_RETRY]) and `SERVICE_UNAVAILABLE`
 * (classified [RetryType.EXPONENTIAL_RETRY]). Before this policy existed the
 * library surfaced that straight to consumers as a
 * [BillingConnectionResult.Error]. Because the failure arrived as a plain
 * `Error`, every consumer had to recognize the transient case by
 * pattern-matching the exception subtype (+ response code) and decide to
 * ignore or retry it — easy to get subtly wrong (e.g. guarding on
 * `responseCode == 0` when `SERVICE_DISCONNECTED` is actually `-1`, so the
 * guard never matches and every transient disconnect is logged as a
 * non-actionable crash report).
 *
 * The library already *knows* these failures are retryable — it attaches a
 * [RetryType] to each. This policy lets it act on that knowledge: it retries
 * the connection internally (bounded attempts + backoff) and only emits a
 * terminal [BillingConnectionResult.Error] once the transient condition turns
 * out to be persistent — which is the point at which the error is actually
 * worth acting on.
 *
 * This mirrors the operation-level retry loop already applied to every
 * [BillingActions] call; connection setup was simply the one path that had no
 * retry layer of its own. The default backoff values match that loop.
 *
 * Failures classified [RetryType.REQUERY_PURCHASE_RETRY] or [RetryType.NONE]
 * are terminal at the connection layer (requerying owned purchases is
 * meaningless before a connection exists), so they surface immediately
 * regardless of this policy.
 *
 * @property maxAttempts Total number of connection attempts, including the
 *   first. Must be `>= 1`. A value of `1` disables internal retry — the
 *   first transient failure surfaces immediately (the pre-policy behavior).
 *   Defaults to [DEFAULT_MAX_ATTEMPTS].
 * @property simpleRetryBackoffMillis Fixed delay between attempts for a
 *   [RetryType.SIMPLE_RETRY]-classified failure. PBL 8+ auto-reconnection
 *   usually re-establishes a dropped IPC connection within a short window, so
 *   a brief fixed pause is enough. Must be `>= 0`. Defaults to
 *   [DEFAULT_SIMPLE_RETRY_BACKOFF_MILLIS].
 * @property exponentialBaseBackoffMillis Initial delay for a
 *   [RetryType.EXPONENTIAL_RETRY]-classified failure (e.g.
 *   `SERVICE_UNAVAILABLE`, `NETWORK_ERROR`). Grows by
 *   [exponentialBackoffFactor] after each exponential retry. Must be `>= 0`.
 *   Defaults to [DEFAULT_EXPONENTIAL_BASE_BACKOFF_MILLIS].
 * @property exponentialBackoffFactor Multiplier applied to the exponential
 *   delay after each [RetryType.EXPONENTIAL_RETRY] attempt. Must be `>= 1`.
 *   Defaults to [DEFAULT_EXPONENTIAL_BACKOFF_FACTOR].
 */
public data class ConnectionRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val simpleRetryBackoffMillis: Long = DEFAULT_SIMPLE_RETRY_BACKOFF_MILLIS,
    val exponentialBaseBackoffMillis: Long = DEFAULT_EXPONENTIAL_BASE_BACKOFF_MILLIS,
    val exponentialBackoffFactor: Int = DEFAULT_EXPONENTIAL_BACKOFF_FACTOR
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, was $maxAttempts" }
        require(simpleRetryBackoffMillis >= 0) {
            "simpleRetryBackoffMillis must be >= 0, was $simpleRetryBackoffMillis"
        }
        require(exponentialBaseBackoffMillis >= 0) {
            "exponentialBaseBackoffMillis must be >= 0, was $exponentialBaseBackoffMillis"
        }
        require(exponentialBackoffFactor >= 1) {
            "exponentialBackoffFactor must be >= 1, was $exponentialBackoffFactor"
        }
    }

    public companion object {
        /** Default total connection attempts, including the first. Mirrors the operation-level retry loop. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 4

        /** Default fixed delay for [RetryType.SIMPLE_RETRY] connection failures. */
        public const val DEFAULT_SIMPLE_RETRY_BACKOFF_MILLIS: Long = 500L

        /** Default initial delay for [RetryType.EXPONENTIAL_RETRY] connection failures. */
        public const val DEFAULT_EXPONENTIAL_BASE_BACKOFF_MILLIS: Long = 2000L

        /** Default multiplier applied to the exponential delay after each retry. */
        public const val DEFAULT_EXPONENTIAL_BACKOFF_FACTOR: Int = 2

        /**
         * Disables internal connection retry: the first transient
         * `startConnection` failure surfaces immediately as a
         * [BillingConnectionResult.Error]. This is the pre-policy behavior,
         * provided for consumers that run their own connection-retry layer.
         */
        public val None: ConnectionRetryPolicy = ConnectionRetryPolicy(maxAttempts = 1)
    }
}
