package com.kanetik.billing.exception

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.kanetik.billing.BillingLoggingUtils
import com.kanetik.billing.RetryType

sealed class BillingException(
    val result: BillingResult?,
    val retryType: RetryType = RetryType.NONE
) : Exception(
    result?.let {
        BillingLoggingUtils.createDetailedBillingContext(it, operationContext = "Exception Creation")
    } ?: "Billing exception with null result"
) {

    companion object {
        fun fromResult(result: BillingResult): BillingException {
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
     * A network error occurred during the operation.
     */
    class NetworkErrorException(result: BillingResult) : BillingException(result, RetryType.EXPONENTIAL_RETRY)

    /**
     * Requested feature is not supported by Play Store on the current device.
     */
    class FeatureNotSupportedException(result: BillingResult) : BillingException(result)

    /**
     * Play Store service is not connected now - potentially transient state.
     *
     * Since PBL 8's [com.android.billingclient.api.BillingClient.Builder.enableAutoServiceReconnection]
     * is on, the library reconnects in the background; a short retry gives it time to do so
     * before we surface the error to the caller.
     */
    class ServiceDisconnectedException(result: BillingResult) : BillingException(result, RetryType.SIMPLE_RETRY)

    /**
     * User pressed back or canceled a dialog.
     */
    class UserCanceledException(result: BillingResult) : BillingException(result)

    /**
     * The service is currently unavailable.
     */
    class ServiceUnavailableException(result: BillingResult) : BillingException(result, RetryType.EXPONENTIAL_RETRY)

    /**
     * Billing API version is not supported for the type requested.
     */
    class BillingUnavailableException(result: BillingResult) : BillingException(result)

    /**
     * Requested product is not available for purchase.
     */
    class ItemUnavailableException(result: BillingResult) : BillingException(result)

    /**
     * Invalid arguments provided to the API.
     */
    class DeveloperErrorException(result: BillingResult) : BillingException(result)

    /**
     * Fatal error during the API action.
     */
    class FatalErrorException(result: BillingResult) : BillingException(result, RetryType.EXPONENTIAL_RETRY)

    /**
     * Failure to purchase since item is already owned.
     */
    class ItemAlreadyOwnedException(result: BillingResult) : BillingException(result, RetryType.REQUERY_PURCHASE_RETRY)

    /**
     * Failure to consume since item is not owned.
     */
    class ItemNotOwnedException(result: BillingResult) : BillingException(result, RetryType.REQUERY_PURCHASE_RETRY)

    /**
     * Unknown - not defined by the billing docs
     */
    class UnknownException(result: BillingResult) : BillingException(result)
}
