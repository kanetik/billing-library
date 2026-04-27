package com.kanetik.billing

import android.util.Log
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import com.android.billingclient.api.BillingResult

/**
 * Enhanced logging utilities for Billing Library 8 that provide detailed context
 * for billing failures to aid in troubleshooting purchase issues.
 *
 * Includes support for sub-response codes introduced in Billing Library 8.0.0
 * which provide more specific reasons for billing failures.
 */
object BillingLoggingUtils {

    /**
     * Creates a detailed billing failure context string with enhanced information
     * available in Billing Library 8, including sub-response codes.
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

            billingResult.debugMessage.takeIf { it.isNotBlank() }?.let {
                add("Debug: '$it'")
            }

            if (attemptCount > 0) {
                add("Attempts: $attemptCount")
            }
        }.joinToString(", ")
    }

    /**
     * Logs billing failures with enhanced context for better troubleshooting.
     * Uses the detailed context available in Billing Library 8, including sub-response codes.
     */
    fun logBillingFailure(
        tag: String,
        billingResult: BillingResult,
        attemptCount: Int = 0,
        operationContext: String? = null,
        additionalContext: Map<String, Any?>? = null
    ) {
        val baseContext = createDetailedBillingContext(billingResult, attemptCount, operationContext)

        val fullContextBuilder = StringBuilder(baseContext)

        // Add any additional context provided
        if (!additionalContext.isNullOrEmpty()) {
            val additionalInfo = additionalContext.entries
                .filter { it.value != null }
                .joinToString(", ") { "${it.key}: ${it.value}" }

            if (additionalInfo.isNotEmpty()) {
                fullContextBuilder.append(", Additional: [$additionalInfo]")
            }
        }

        Log.w(tag, "Billing failure - $fullContextBuilder")
    }

    /**
     * Logs billing flow launch failures with special emphasis on sub-response codes
     * that provide more specific context about why the billing flow failed.
     * This is particularly useful for launchBillingFlow() failures in Billing Library 8.
     */
    fun logBillingFlowFailure(
        tag: String,
        billingResult: BillingResult,
        additionalContext: Map<String, Any?>? = null
    ) {
        // Use specific operation context for billing flow launches
        logBillingFailure(
            tag = tag,
            billingResult = billingResult,
            operationContext = "Launch Billing Flow",
            additionalContext = additionalContext
        )

        // Log additional information if sub-response code provides specific details
        val subResponseCode = billingResult.onPurchasesUpdatedSubResponseCode
        if (subResponseCode == OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS) {
            Log.w(tag, "Billing flow failed due to insufficient funds - user may need to add payment method or check balance")
        }
    }

    /**
     * Provides human-readable descriptions for billing response codes
     * to make logs more understandable for troubleshooting.
     */
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

    /**
     * Provides human-readable descriptions for sub-response codes introduced in Billing Library 8
     * to provide more specific context for billing failures.
     */
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
