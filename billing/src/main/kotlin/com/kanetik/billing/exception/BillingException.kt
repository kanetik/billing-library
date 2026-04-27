package com.kanetik.billing.exception

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.kanetik.billing.BillingLoggingUtils
import com.kanetik.billing.RetryType

/**
 * Typed wrapper for a Play Billing failure.
 *
 * Every [com.kanetik.billing.BillingActions] method that fails throws a concrete
 * subtype of [BillingException] with:
 *  - the original [BillingResult] in [result] (response code, sub-response code,
 *    debug message),
 *  - a [retryType] hint indicating whether the library will retry the call.
 *
 * Branch on the subtype (or check [retryType]) to decide UX: show a "no
 * connection, try again" toast for [NetworkErrorException], a "this purchase is
 * already yours" message for [ItemAlreadyOwnedException], etc.
 *
 * The library applies [retryType] internally inside its retry loop; you'll only
 * see an exception thrown when the retry budget is exhausted or the error is
 * terminal.
 */
public sealed class BillingException(
    public val result: BillingResult?,
    public val retryType: RetryType = RetryType.NONE
) : Exception(
    result?.let {
        BillingLoggingUtils.createDetailedBillingContext(it, operationContext = "Exception Creation")
    } ?: "Billing exception with null result"
) {

    public companion object {
        /**
         * Maps a [BillingResult] to the matching [BillingException] subtype.
         * The library uses this internally; consumers shouldn't typically need
         * to call it.
         */
        public fun fromResult(result: BillingResult): BillingException {
            return when (result.responseCode) {
                BillingResponseCode.NETWORK_ERROR -> NetworkErrorException(result)
                BillingResponseCode.FEATURE_NOT_SUPPORTED -> FeatureNotSupportedException(result)
                BillingResponseCode.SERVICE_DISCONNECTED -> ServiceDisconnectedException(result)
                BillingResponseCode.USER_CANCELED -> UserCanceledException(result)
                BillingResponseCode.SERVICE_UNAVAILABLE -> ServiceUnavailableException(result)
                BillingResponseCode.BILLING_UNAVAILABLE -> BillingUnavailableException(result)
                BillingResponseCode.ITEM_UNAVAILABLE -> ItemUnavailableException(result)
                BillingResponseCode.DEVELOPER_ERROR -> DeveloperErrorException(result)
                BillingResponseCode.ERROR -> FatalErrorException(result)
                BillingResponseCode.ITEM_ALREADY_OWNED -> ItemAlreadyOwnedException(result)
                BillingResponseCode.ITEM_NOT_OWNED -> ItemNotOwnedException(result)
                else -> UnknownException(result)
            }
        }
    }

    /**
     * Network connectivity issue talking to Play Store. Transient — the library
     * retries with exponential backoff. After the retry budget is spent, surface
     * to the user as "no connection".
     *
     * Retry strategy: [RetryType.EXPONENTIAL_RETRY].
     */
    public class NetworkErrorException(result: BillingResult) : BillingException(result, RetryType.EXPONENTIAL_RETRY)

    /**
     * The Play Store on this device doesn't support the requested feature
     * (e.g. subscriptions on a region/install that doesn't allow them). Terminal —
     * the user's device or Play Store install can't fulfill this request.
     *
     * Retry strategy: [RetryType.NONE]. Show a "feature not available" message.
     */
    public class FeatureNotSupportedException(result: BillingResult) : BillingException(result)

    /**
     * The internal `BillingClient` ↔ Play Store IPC connection dropped.
     *
     * Since PBL 8's
     * [com.android.billingclient.api.BillingClient.Builder.enableAutoServiceReconnection]
     * is on, the underlying client reconnects in the background; a short fixed
     * delay gives it time to do so before the library surfaces the error to the
     * caller.
     *
     * Retry strategy: [RetryType.SIMPLE_RETRY].
     */
    public class ServiceDisconnectedException(result: BillingResult) : BillingException(result, RetryType.SIMPLE_RETRY)

    /**
     * User dismissed the purchase flow (back button, X tap). Not really an error
     * from the app's perspective — but it's thrown so the library can unify all
     * non-success outcomes through one channel.
     *
     * Retry strategy: [RetryType.NONE]. Don't retry; the user said no.
     */
    public class UserCanceledException(result: BillingResult) : BillingException(result)

    /**
     * Play Store service is unreachable (overloaded, restarting, broken on this
     * device). Often transient.
     *
     * Retry strategy: [RetryType.EXPONENTIAL_RETRY].
     */
    public class ServiceUnavailableException(result: BillingResult) : BillingException(result, RetryType.EXPONENTIAL_RETRY)

    /**
     * Billing API itself is unavailable on this device — different from
     * [ServiceUnavailableException] in that this means "this device/install can
     * never use billing", not "service is busy".
     *
     * Common causes:
     *  - The user is on a non-Play distribution (e.g. some Huawei devices).
     *  - Play Services has been disabled or never installed.
     *  - The user's account isn't eligible for purchases.
     *
     * Retry strategy: [RetryType.NONE]. Show "in-app purchases not available on
     * this device" UX; consider hiding billing-related buttons entirely until
     * billing becomes available.
     */
    public class BillingUnavailableException(result: BillingResult) : BillingException(result)

    /**
     * The product ID isn't available for purchase on this account / region /
     * Play Store. Common causes: typo in the product ID, product not yet
     * activated in Play Console, geo-restriction.
     *
     * Retry strategy: [RetryType.NONE].
     */
    public class ItemUnavailableException(result: BillingResult) : BillingException(result)

    /**
     * The library or app called a Play Billing API with malformed arguments.
     * **This is a bug in your code, not a runtime condition** — the exception
     * carries a debug message in [result] explaining what was wrong.
     *
     * Common causes (under PBL 8):
     *  - Forgetting to pass `offerToken` for a one-time product (fix: use
     *    [com.kanetik.billing.ext.toOneTimeFlowParams]).
     *  - Launching a flow against a finishing/destroyed activity (fix: use
     *    [com.kanetik.billing.ext.validatePurchaseActivity]).
     *  - Passing an obviously-invalid product ID.
     *
     * Retry strategy: [RetryType.NONE]. Fix the calling code; retrying won't help.
     */
    public class DeveloperErrorException(result: BillingResult) : BillingException(result)

    /**
     * Internal Play Billing error with no specific cause. The catch-all for
     * anything Play didn't classify. Retrying with backoff sometimes works
     * because Play's internals can self-heal.
     *
     * Retry strategy: [RetryType.EXPONENTIAL_RETRY].
     */
    public class FatalErrorException(result: BillingResult) : BillingException(result, RetryType.EXPONENTIAL_RETRY)

    /**
     * Tried to purchase a non-consumable product the user already owns. Often
     * caused by stale local state — the library re-queries owned purchases and
     * retries, which usually surfaces the existing purchase rather than failing.
     *
     * Retry strategy: [RetryType.REQUERY_PURCHASE_RETRY].
     */
    public class ItemAlreadyOwnedException(result: BillingResult) : BillingException(result, RetryType.REQUERY_PURCHASE_RETRY)

    /**
     * Tried to consume a purchase the user doesn't own. Mirror of
     * [ItemAlreadyOwnedException]; same recovery strategy.
     *
     * Retry strategy: [RetryType.REQUERY_PURCHASE_RETRY].
     */
    public class ItemNotOwnedException(result: BillingResult) : BillingException(result, RetryType.REQUERY_PURCHASE_RETRY)

    /**
     * Response code that PBL doesn't document. Should be vanishingly rare; the
     * library wraps it so callers always have a typed channel.
     *
     * Retry strategy: [RetryType.NONE]. If you see this in production, log it —
     * Play may have introduced a new response code worth handling explicitly.
     */
    public class UnknownException(result: BillingResult) : BillingException(result)
}
