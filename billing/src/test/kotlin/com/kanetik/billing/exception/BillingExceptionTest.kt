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

    // userFacingCategory mapping — these tests lock in the public bucketing.
    // Re-classifying a subtype into the wrong UI bucket would silently change
    // app UX without breaking compilation, which is the threat model here.

    @Test
    fun `userFacingCategory maps UserCanceled`() {
        assertThat(BillingException.fromResult(result(BillingResponseCode.USER_CANCELED)).userFacingCategory)
            .isEqualTo(BillingErrorCategory.UserCanceled)
    }

    @Test
    fun `userFacingCategory maps Network bucket`() {
        listOf(
            BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.SERVICE_UNAVAILABLE
        ).forEach { code ->
            assertThat(BillingException.fromResult(result(code)).userFacingCategory)
                .isEqualTo(BillingErrorCategory.Network)
        }
    }

    @Test
    fun `userFacingCategory maps BillingUnavailable bucket`() {
        // BillingUnavailableException + FeatureNotSupportedException both
        // represent runtime device-state conditions ("this isn't available
        // on your device") and share the same UI bucket.
        listOf(
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.FEATURE_NOT_SUPPORTED
        ).forEach { code ->
            assertThat(BillingException.fromResult(result(code)).userFacingCategory)
                .isEqualTo(BillingErrorCategory.BillingUnavailable)
        }
    }

    @Test
    fun `userFacingCategory maps ProductUnavailable bucket`() {
        listOf(
            BillingResponseCode.ITEM_UNAVAILABLE,
            BillingResponseCode.ITEM_ALREADY_OWNED,
            BillingResponseCode.ITEM_NOT_OWNED
        ).forEach { code ->
            assertThat(BillingException.fromResult(result(code)).userFacingCategory)
                .isEqualTo(BillingErrorCategory.ProductUnavailable)
        }
    }

    @Test
    fun `userFacingCategory maps DeveloperError`() {
        assertThat(BillingException.fromResult(result(BillingResponseCode.DEVELOPER_ERROR)).userFacingCategory)
            .isEqualTo(BillingErrorCategory.DeveloperError)
    }

    @Test
    fun `userFacingCategory maps Other bucket`() {
        // FatalErrorException (BillingResponseCode.ERROR) and UnknownException (anything else)
        listOf(
            BillingResponseCode.ERROR,
            9999  // unknown — maps to UnknownException
        ).forEach { code ->
            assertThat(BillingException.fromResult(result(code)).userFacingCategory)
                .isEqualTo(BillingErrorCategory.Other)
        }
    }

    @Test
    fun `WrappedException carries original cause and maps to Other bucket`() {
        val original = IllegalStateException("test")
        val wrapped = BillingException.WrappedException(original)

        assertThat(wrapped.originalCause).isSameInstanceAs(original)
        assertThat(wrapped.cause).isSameInstanceAs(original)
        assertThat(wrapped.result).isNull()
        assertThat(wrapped.message).contains("IllegalStateException")
        assertThat(wrapped.message).contains("test")
        assertThat(wrapped.userFacingCategory).isEqualTo(BillingErrorCategory.Other)
    }

    private fun result(responseCode: Int, debugMessage: String = ""): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setDebugMessage(debugMessage)
            .build()
}
