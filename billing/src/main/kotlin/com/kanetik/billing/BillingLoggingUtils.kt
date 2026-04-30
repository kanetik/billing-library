package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import com.android.billingclient.api.BillingResult
import com.kanetik.billing.logging.BillingLogger

/**
 * Internal logging helpers for Billing Library 8 that build detailed context
 * strings for billing failures, including sub-response codes introduced in
 * PBL 8.0.0. All output flows through a [BillingLogger] supplied by the caller —
 * the consumer's logger decides whether anything is actually emitted.
 */
internal object BillingLoggingUtils {

    /**
     * Builds a detailed billing failure context string with the enhanced information
     * available in Billing Library 8 (response code, sub-response code, debug message).
     */
    fun createDetailedBillingContext(
        billingResult: BillingResult,
        attemptCount: Int = 0,
        operationContext: String? = null
    ): String {
        return buildList {
            operationContext?.let { add("Operation: $it") }
            add("Code: ${billingResult.responseCode} (${getResponseCodeDescription(billingResult.responseCode)})")

            val subResponseCode = billingResult.onPurchasesUpdatedSubResponseCode
            if (subResponseCode != OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE) {
                add("SubCode: ${getSubResponseCodeDescription(subResponseCode)}")
            }

            // debugMessage is a Kotlin platform-type String! (PBL Java); the no-arg
            // BillingResult() constructor leaves it null, so guard against that
            // before calling isNotBlank().
            billingResult.debugMessage?.takeIf { it.isNotBlank() }?.let {
                add("Debug: '$it'")
            }

            if (attemptCount > 0) {
                add("Attempts: $attemptCount")
            }
        }.joinToString(", ")
    }

    /**
     * Logs billing failures with enhanced PBL 8 context (sub-response codes etc.) at warn level.
     */
    fun logBillingFailure(
        logger: BillingLogger,
        billingResult: BillingResult,
        attemptCount: Int = 0,
        operationContext: String? = null,
        additionalContext: Map<String, Any?>? = null
    ) {
        val baseContext = createDetailedBillingContext(billingResult, attemptCount, operationContext)

        val fullContextBuilder = StringBuilder(baseContext)

        if (!additionalContext.isNullOrEmpty()) {
            val additionalInfo = additionalContext.entries
                .filter { it.value != null }
                .joinToString(", ") { "${it.key}: ${it.value}" }

            if (additionalInfo.isNotEmpty()) {
                fullContextBuilder.append(", Additional: [$additionalInfo]")
            }
        }

        logger.w("Billing failure - $fullContextBuilder")
    }

    /**
     * Logs billing-flow launch failures with extra emphasis on sub-response codes
     * that explain why the flow failed (e.g. insufficient funds).
     */
    fun logBillingFlowFailure(
        logger: BillingLogger,
        billingResult: BillingResult,
        additionalContext: Map<String, Any?>? = null
    ) {
        logBillingFailure(
            logger = logger,
            billingResult = billingResult,
            operationContext = "Launch Billing Flow",
            additionalContext = additionalContext
        )

        val subResponseCode = billingResult.onPurchasesUpdatedSubResponseCode
        if (subResponseCode == OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS) {
            logger.w("Billing flow failed due to insufficient funds - user may need to add payment method or check balance")
        }
    }

    private fun getResponseCodeDescription(responseCode: Int): String {
        return when (responseCode) {
            BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Feature Not Supported"
            BillingResponseCode.SERVICE_DISCONNECTED -> "Service Disconnected"
            BillingResponseCode.OK -> "Success"
            BillingResponseCode.USER_CANCELED -> "User Canceled"
            BillingResponseCode.SERVICE_UNAVAILABLE -> "Service Unavailable"
            BillingResponseCode.BILLING_UNAVAILABLE -> "Billing Unavailable"
            BillingResponseCode.ITEM_UNAVAILABLE -> "Item Unavailable"
            BillingResponseCode.DEVELOPER_ERROR -> "Developer Error"
            BillingResponseCode.ERROR -> "Fatal Error"
            BillingResponseCode.ITEM_ALREADY_OWNED -> "Item Already Owned"
            BillingResponseCode.ITEM_NOT_OWNED -> "Item Not Owned"
            BillingResponseCode.NETWORK_ERROR -> "Network Error"
            else -> "Unknown ($responseCode)"
        }
    }

    private fun getSubResponseCodeDescription(subResponseCode: Int): String {
        return when (subResponseCode) {
            OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS ->
                "Payment Declined - Insufficient funds"

            OnPurchasesUpdatedSubResponseCode.USER_INELIGIBLE ->
                "Payment Declined - User does not currently meet offer eligibility requirements"

            OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE -> "No Sub-Response"
            else -> "No Sub-Response ($subResponseCode)"
        }
    }
}
