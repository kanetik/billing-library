package com.kanetik.billing.exception

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.kanetik.billing.BillingLoggingUtils
import com.kanetik.billing.RetryType

/**
 * Typed wrapper for a Play Billing failure.
 *
 * Most [com.kanetik.billing.BillingActions] methods that fail throw a concrete
 * subtype of [BillingException] — [com.kanetik.billing.BillingActions.queryPurchases],
 * [com.kanetik.billing.BillingActions.queryProductDetails],
 * [com.kanetik.billing.BillingActions.consumePurchase],
 * [com.kanetik.billing.BillingActions.acknowledgePurchase],
 * [com.kanetik.billing.BillingActions.launchFlow],
 * [com.kanetik.billing.BillingActions.showInAppMessages]. The high-level
 * [com.kanetik.billing.BillingActions.handlePurchase] helper is the exception:
 * it returns a sealed [com.kanetik.billing.HandlePurchaseResult] with a
 * `Failure(BillingException)` variant instead, so consumers can't accidentally
 * grant entitlement on a swallowed exception.
 *
 * Each subtype carries:
 *  - the original [BillingResult] in [result] (response code, sub-response code,
 *    debug message),
 *  - a [retryType] hint indicating whether the library will retry the call.
 *
 * Branch on the subtype (or check [retryType] / [userFacingCategory]) to decide
 * UX: show a "no connection, try again" toast for [NetworkErrorException], a
 * "this purchase is already yours" message for [ItemAlreadyOwnedException], etc.
 *
 * The library applies [retryType] internally inside its retry loop; you'll only
 * see an exception thrown when the retry budget is exhausted or the error is
 * terminal.
 *
 * ## ⚠️ Never display [message] to end users
 *
 * [message] is a **debug-context dump** — class name, response code, sub-response
 * code, debug message. Useful for logs, Crashlytics, and developer dashboards.
 * **Awful for user-facing dialogs** (leaks `ServiceDisconnectedException`,
 * `BILLING_RESPONSE_CODE_3`, internal Play debug strings into your UI).
 *
 * For UI: branch on the subtype directly, or call [userFacingCategory] to
 * collapse the 12 subtypes into [BillingErrorCategory]'s 6 buckets and
 * localize per bucket from your own string resources. Example:
 *
 * ```
 * catch (e: BillingException) {
 *     val msgRes = when (e.userFacingCategory) {
 *         BillingErrorCategory.UserCanceled -> return  // not really an error
 *         BillingErrorCategory.Network -> R.string.purchase_error_network
 *         BillingErrorCategory.BillingUnavailable -> R.string.purchase_error_billing_unavailable
 *         BillingErrorCategory.ProductUnavailable -> R.string.purchase_error_product_unavailable
 *         BillingErrorCategory.DeveloperError -> R.string.purchase_error_generic
 *         BillingErrorCategory.Other -> R.string.purchase_error_generic
 *     }
 *     showError(getString(msgRes))
 *     log.e("Billing failure", e)  // .message is fine here — it's a log
 * }
 * ```
 */
public sealed class BillingException(
    public val result: BillingResult?,
    public val retryType: RetryType = RetryType.NONE
) : Exception() {

    /**
     * **Debug-context dump for logs only — never display to end users.** See the
     * class-level KDoc for the user-facing UX pattern (use [userFacingCategory]).
     *
     * Built lazily so a [BillingException] instance constructed but never thrown
     * doesn't pay the cost of building the context string. In practice most
     * exceptions get their message read by Crashlytics/Timber/error UIs, so
     * laziness is mostly a hygiene improvement — but it keeps construction cheap.
     */
    override val message: String? by lazy {
        result?.let {
            BillingLoggingUtils.createDetailedBillingContext(it, operationContext = "Exception Creation")
        } ?: "Billing exception with null result"
    }

    /**
     * UI bucket for this exception. Collapses the 12 sealed subtypes into
     * [BillingErrorCategory]'s ~6 user-facing categories so callers can
     * localize from a small string-resource map instead of branching on
     * every PBL response code. See the class-level KDoc for the recommended
     * pattern.
     */
    public val userFacingCategory: BillingErrorCategory
        get() = when (this) {
            is UserCanceledException -> BillingErrorCategory.UserCanceled
            is NetworkErrorException,
            is ServiceDisconnectedException,
            is ServiceUnavailableException -> BillingErrorCategory.Network
            is BillingUnavailableException -> BillingErrorCategory.BillingUnavailable
            is ItemUnavailableException,
            is ItemAlreadyOwnedException,
            is ItemNotOwnedException -> BillingErrorCategory.ProductUnavailable
            is DeveloperErrorException,
            is FeatureNotSupportedException -> BillingErrorCategory.DeveloperError
            is FatalErrorException,
            is UnknownException -> BillingErrorCategory.Other
        }

    /**
     * Class-aware [toString] so logs show the concrete subtype name plus the
     * full context string (response code, sub-response, debug message,
     * retryType). PBL's [BillingResult] lacks content-based equality, so
     * [equals] / [hashCode] stay identity-based — comparing two instances by
     * value is rarely useful for exceptions anyway.
     */
    override fun toString(): String =
        "${this::class.simpleName}(retryType=$retryType, message=$message)"


    public companion object {
        /**
         * Maps a [BillingResult] to the matching [BillingException] subtype.
         *
         * Library-internal: the public [BillingException] subtypes have public
         * constructors if a consumer providing a custom
         * [com.kanetik.billing.factory.BillingClientFactory] needs to throw a
         * specific type directly.
         */
        internal fun fromResult(result: BillingResult): BillingException {
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
