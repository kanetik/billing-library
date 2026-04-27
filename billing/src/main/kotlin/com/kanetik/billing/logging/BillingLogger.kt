package com.kanetik.billing.logging

import android.util.Log

/**
 * Sink for diagnostic messages emitted by Kanetik Billing Library.
 *
 * The library is silent by default — [BillingLogger.Noop] is the no-op
 * implementation wired in unless a consumer opts in. Three levels are
 * provided, matching the events the library actually emits:
 *
 *  - [d] (debug): connection-state transitions, retry attempts, success
 *    chatter useful during development.
 *  - [w] (warn): unexpected protocol responses that the library recovers
 *    from (retry fall-through, fallback paths).
 *  - [e] (error): rare events the library can't propagate as a typed
 *    [com.kanetik.billing.exception.BillingException] (e.g. internal
 *    invariant violations, dropped purchase updates).
 *
 * Most error conditions surface as a typed [com.kanetik.billing.exception.BillingException]
 * rather than going through this logger — consumers handle those by catching
 * and deciding what to log/report. The logger covers the gaps where typed
 * propagation isn't possible.
 *
 * ## Routing to Timber, Crashlytics, etc.
 *
 * Implement this interface in your app and forward each method to your
 * preferred sink. The library has zero dependency on Timber, Crashlytics,
 * or any other logging library — that's left entirely to the consumer.
 *
 * Example: route warnings/errors to Crashlytics while keeping debug logs
 * out of release builds.
 *
 * ```
 * class MyBillingLogger(private val crashlytics: FirebaseCrashlytics) : BillingLogger {
 *     override fun d(message: String, throwable: Throwable?) {
 *         if (BuildConfig.DEBUG) Log.d("Billing", message, throwable)
 *     }
 *     override fun w(message: String, throwable: Throwable?) {
 *         Log.w("Billing", message, throwable)
 *         crashlytics.log("W/Billing: $message")
 *     }
 *     override fun e(message: String, throwable: Throwable?) {
 *         Log.e("Billing", message, throwable)
 *         crashlytics.recordException(throwable ?: BillingNonFatal(message))
 *     }
 * }
 * ```
 */
public interface BillingLogger {
    public fun d(message: String, throwable: Throwable? = null)
    public fun w(message: String, throwable: Throwable? = null)
    public fun e(message: String, throwable: Throwable? = null)

    public companion object {
        /** Discards every message. Default — the library is silent unless opted in. */
        public val Noop: BillingLogger = NoopBillingLogger

        /** Forwards every message to [android.util.Log] under the tag `KanetikBilling`. */
        public val Android: BillingLogger = AndroidLogLogger
    }
}

internal object NoopBillingLogger : BillingLogger {
    override fun d(message: String, throwable: Throwable?) = Unit
    override fun w(message: String, throwable: Throwable?) = Unit
    override fun e(message: String, throwable: Throwable?) = Unit
}

internal object AndroidLogLogger : BillingLogger {
    private const val TAG = "KanetikBilling"
    override fun d(message: String, throwable: Throwable?) {
        if (throwable != null) Log.d(TAG, message, throwable) else Log.d(TAG, message)
    }
    override fun w(message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }
    override fun e(message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
