package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import com.android.billingclient.api.BillingResult
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.logging.BillingLogger
import org.junit.Test

class BillingLoggingUtilsTest {

    @Test
    fun `createDetailedBillingContext includes response code description`() {
        val ctx = BillingLoggingUtils.createDetailedBillingContext(
            billingResult = result(BillingResponseCode.NETWORK_ERROR, "lost wifi")
        )
        assertThat(ctx).contains("Code: 12") // NETWORK_ERROR is response code 12
        assertThat(ctx).contains("Network Error")
        assertThat(ctx).contains("Debug: 'lost wifi'")
    }

    @Test
    fun `createDetailedBillingContext omits debug when message is blank`() {
        val ctx = BillingLoggingUtils.createDetailedBillingContext(
            billingResult = result(BillingResponseCode.OK, "")
        )
        assertThat(ctx).doesNotContain("Debug:")
    }

    @Test
    fun `createDetailedBillingContext survives null debug message`() {
        // BillingResult() (no-arg) leaves debugMessage null — exercised by the
        // CoroutinesBillingConnectionFactory error-fallback path.
        val ctx = BillingLoggingUtils.createDetailedBillingContext(
            billingResult = BillingResult()
        )
        // No throw is the assertion; debug section is omitted because null is "blank".
        assertThat(ctx).doesNotContain("Debug:")
    }

    @Test
    fun `createDetailedBillingContext includes operation context when supplied`() {
        val ctx = BillingLoggingUtils.createDetailedBillingContext(
            billingResult = result(BillingResponseCode.OK),
            operationContext = "Launch Billing Flow"
        )
        assertThat(ctx).startsWith("Operation: Launch Billing Flow")
    }

    @Test
    fun `createDetailedBillingContext includes attempt count when greater than zero`() {
        val ctx = BillingLoggingUtils.createDetailedBillingContext(
            billingResult = result(BillingResponseCode.NETWORK_ERROR),
            attemptCount = 3
        )
        assertThat(ctx).contains("Attempts: 3")
    }

    @Test
    fun `createDetailedBillingContext omits attempt count when zero`() {
        val ctx = BillingLoggingUtils.createDetailedBillingContext(
            billingResult = result(BillingResponseCode.OK),
            attemptCount = 0
        )
        assertThat(ctx).doesNotContain("Attempts:")
    }

    @Test
    fun `logBillingFailure routes to logger w with full context`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.NETWORK_ERROR, "timeout"),
            attemptCount = 2,
            operationContext = "Query Purchases",
            additionalContext = mapOf("RetryType" to "EXPONENTIAL_RETRY")
        )
        assertThat(captor.warnings).hasSize(1)
        val msg = captor.warnings.single().first
        assertThat(msg).startsWith("Billing failure - ")
        assertThat(msg).contains("Operation: Query Purchases")
        assertThat(msg).contains("Network Error")
        assertThat(msg).contains("Debug: 'timeout'")
        assertThat(msg).contains("Attempts: 2")
        assertThat(msg).contains("RetryType: EXPONENTIAL_RETRY")
    }

    @Test
    fun `logBillingFailure routes USER_CANCELED to logger d, not w`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.USER_CANCELED)
        )
        assertThat(captor.warnings).isEmpty()
        assertThat(captor.debugs).hasSize(1)
        assertThat(captor.debugs.single().first).startsWith("Billing failure - ")
    }

    @Test
    fun `logBillingFailure routes DEVELOPER_ERROR to logger e, not w`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.DEVELOPER_ERROR)
        )
        assertThat(captor.warnings).isEmpty()
        assertThat(captor.errors).hasSize(1)
    }

    @Test
    fun `logBillingFailure routes ITEM_ALREADY_OWNED to logger w without a throwable`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.ITEM_ALREADY_OWNED)
        )
        assertThat(captor.warnings).hasSize(1)
        assertThat(captor.warnings.single().second).isNull()
    }

    @Test
    fun `logBillingFailure filters null additionalContext entries`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.ERROR),
            additionalContext = mapOf("key1" to "value1", "key2" to null)
        )
        val msg = captor.warnings.single().first
        assertThat(msg).contains("key1: value1")
        assertThat(msg).doesNotContain("key2")
    }

    @Test
    fun `logBillingFlowFailure emits exactly one warning when no sub-response code is set`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFlowFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.SERVICE_UNAVAILABLE)
        )
        assertThat(captor.warnings).hasSize(1)
        assertThat(captor.warnings.single().first).contains("Operation: Launch Billing Flow")
    }

    @Test
    fun `logBillingFlowFailure emits exactly one warning regardless of sub-response code`() {
        val captor = CapturingLogger()
        BillingLoggingUtils.logBillingFlowFailure(
            logger = captor,
            billingResult = result(
                BillingResponseCode.BILLING_UNAVAILABLE,
                subResponseCode = OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS
            )
        )
        assertThat(captor.warnings).hasSize(1)
    }

    private fun result(
        responseCode: Int,
        debugMessage: String = "",
        subResponseCode: Int = OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE
    ): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setDebugMessage(debugMessage)
            .setOnPurchasesUpdatedSubResponseCode(subResponseCode)
            .build()

    private class CapturingLogger : BillingLogger {
        val debugs = mutableListOf<Pair<String, Throwable?>>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        val errors = mutableListOf<Pair<String, Throwable?>>()
        override fun d(message: String, throwable: Throwable?) {
            debugs += message to throwable
        }
        override fun w(message: String, throwable: Throwable?) {
            warnings += message to throwable
        }
        override fun e(message: String, throwable: Throwable?) {
            errors += message to throwable
        }
    }
}
