package com.kanetik.billing.exception

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.RetryType
import org.junit.Test

class BillingExceptionTest {

    @Test
    fun `fromResult maps NETWORK_ERROR to NetworkErrorException with EXPONENTIAL_RETRY`() {
        val result = result(BillingResponseCode.NETWORK_ERROR)
        val ex = BillingException.fromResult(result)
        assertThat(ex).isInstanceOf(BillingException.NetworkErrorException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.EXPONENTIAL_RETRY)
        assertThat(ex.result).isSameInstanceAs(result)
    }

    @Test
    fun `fromResult maps FEATURE_NOT_SUPPORTED to FeatureNotSupportedException with NONE`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.FEATURE_NOT_SUPPORTED))
        assertThat(ex).isInstanceOf(BillingException.FeatureNotSupportedException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.NONE)
    }

    @Test
    fun `fromResult maps SERVICE_DISCONNECTED to ServiceDisconnectedException with SIMPLE_RETRY`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.SERVICE_DISCONNECTED))
        assertThat(ex).isInstanceOf(BillingException.ServiceDisconnectedException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.SIMPLE_RETRY)
    }

    @Test
    fun `fromResult maps USER_CANCELED to UserCanceledException with NONE`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.USER_CANCELED))
        assertThat(ex).isInstanceOf(BillingException.UserCanceledException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.NONE)
    }

    @Test
    fun `fromResult maps SERVICE_UNAVAILABLE to ServiceUnavailableException with EXPONENTIAL_RETRY`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.SERVICE_UNAVAILABLE))
        assertThat(ex).isInstanceOf(BillingException.ServiceUnavailableException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.EXPONENTIAL_RETRY)
    }

    @Test
    fun `fromResult maps BILLING_UNAVAILABLE to BillingUnavailableException with NONE`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.BILLING_UNAVAILABLE))
        assertThat(ex).isInstanceOf(BillingException.BillingUnavailableException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.NONE)
    }

    @Test
    fun `fromResult maps ITEM_UNAVAILABLE to ItemUnavailableException with NONE`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.ITEM_UNAVAILABLE))
        assertThat(ex).isInstanceOf(BillingException.ItemUnavailableException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.NONE)
    }

    @Test
    fun `fromResult maps DEVELOPER_ERROR to DeveloperErrorException with NONE`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.DEVELOPER_ERROR))
        assertThat(ex).isInstanceOf(BillingException.DeveloperErrorException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.NONE)
    }

    @Test
    fun `fromResult maps ERROR to FatalErrorException with EXPONENTIAL_RETRY`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.ERROR))
        assertThat(ex).isInstanceOf(BillingException.FatalErrorException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.EXPONENTIAL_RETRY)
    }

    @Test
    fun `fromResult maps ITEM_ALREADY_OWNED to ItemAlreadyOwnedException with REQUERY_PURCHASE_RETRY`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.ITEM_ALREADY_OWNED))
        assertThat(ex).isInstanceOf(BillingException.ItemAlreadyOwnedException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.REQUERY_PURCHASE_RETRY)
    }

    @Test
    fun `fromResult maps ITEM_NOT_OWNED to ItemNotOwnedException with REQUERY_PURCHASE_RETRY`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.ITEM_NOT_OWNED))
        assertThat(ex).isInstanceOf(BillingException.ItemNotOwnedException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.REQUERY_PURCHASE_RETRY)
    }

    @Test
    fun `fromResult maps unknown response codes to UnknownException with NONE`() {
        val ex = BillingException.fromResult(result(responseCode = 9999))
        assertThat(ex).isInstanceOf(BillingException.UnknownException::class.java)
        assertThat(ex.retryType).isEqualTo(RetryType.NONE)
    }

    @Test
    fun `message is lazily built and includes the response-code description`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.NETWORK_ERROR, "boom"))
        // First access materializes the lazy message; should contain the response-code label
        // and the debug message.
        val message = ex.message
        assertThat(message).isNotNull()
        assertThat(message).contains("Network Error")
        assertThat(message).contains("boom")
    }

    @Test
    fun `toString includes class name and retry type`() {
        val ex = BillingException.fromResult(result(BillingResponseCode.SERVICE_DISCONNECTED))
        val str = ex.toString()
        assertThat(str).contains("ServiceDisconnectedException")
        assertThat(str).contains("SIMPLE_RETRY")
    }

    @Test
    fun `bare BillingResult constructor yields a buildable message`() {
        // BillingResult() (no-arg) is used in the connection-factory error fallback;
        // debugMessage is null in that path. Verify our message-building survives it.
        val ex = BillingException.UnknownException(BillingResult())
        // No throw is the assertion — accessing .message must succeed even when
        // PBL leaves debugMessage unset.
        assertThat(ex.message).isNotNull()
    }

    private fun result(responseCode: Int, debugMessage: String = ""): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setDebugMessage(debugMessage)
            .build()
}
