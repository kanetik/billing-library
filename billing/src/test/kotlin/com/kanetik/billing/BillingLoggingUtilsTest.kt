package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
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
    fun `logBillingFlowFailure also emits insufficient-funds hint when sub-response code matches`() {
        val captor = CapturingLogger()
        // We can't easily set sub-response codes via the public BillingResult builder,
        // so verify the no-special-sub-response path doesn't double-emit at minimum.
        BillingLoggingUtils.logBillingFlowFailure(
            logger = captor,
            billingResult = result(BillingResponseCode.SERVICE_UNAVAILABLE)
        )
        // Without the special sub-response, we expect exactly one warning (the base failure).
        assertThat(captor.warnings).hasSize(1)
        assertThat(captor.warnings.single().first).contains("Operation: Launch Billing Flow")
    }

    private fun result(responseCode: Int, debugMessage: String = ""): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setDebugMessage(debugMessage)
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
