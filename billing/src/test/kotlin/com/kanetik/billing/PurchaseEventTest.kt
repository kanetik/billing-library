package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import org.junit.Test

class PurchaseEventTest {

    @Test
    fun `Pending instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val a = FlowOutcome.Pending(listOf(purchase), result(BillingResponseCode.OK))
        val b = FlowOutcome.Pending(listOf(purchase), result(BillingResponseCode.OK))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `Canceled instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val a = FlowOutcome.Canceled(listOf(purchase), result(BillingResponseCode.USER_CANCELED))
        val b = FlowOutcome.Canceled(listOf(purchase), result(BillingResponseCode.USER_CANCELED))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `ItemAlreadyOwned instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val a = FlowOutcome.ItemAlreadyOwned(listOf(purchase), result(BillingResponseCode.ITEM_ALREADY_OWNED))
        val b = FlowOutcome.ItemAlreadyOwned(listOf(purchase), result(BillingResponseCode.ITEM_ALREADY_OWNED))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `ItemUnavailable instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val a = FlowOutcome.ItemUnavailable(listOf(purchase), result(BillingResponseCode.ITEM_UNAVAILABLE))
        val b = FlowOutcome.ItemUnavailable(listOf(purchase), result(BillingResponseCode.ITEM_UNAVAILABLE))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `PaymentDeclined instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val a = FlowOutcome.PaymentDeclined(listOf(purchase), result(BillingResponseCode.BILLING_UNAVAILABLE))
        val b = FlowOutcome.PaymentDeclined(listOf(purchase), result(BillingResponseCode.BILLING_UNAVAILABLE))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `Failure instances with the same exception and purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val exception = BillingException.fromResult(result(BillingResponseCode.NETWORK_ERROR))
        val a = FlowOutcome.Failure(exception, listOf(purchase), result(BillingResponseCode.NETWORK_ERROR))
        val b = FlowOutcome.Failure(exception, listOf(purchase), result(BillingResponseCode.NETWORK_ERROR))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `UnknownResponse instances with equal code and purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val a = FlowOutcome.UnknownResponse(999, listOf(purchase), result(999))
        val b = FlowOutcome.UnknownResponse(999, listOf(purchase), result(999))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    private fun result(responseCode: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(responseCode).build()
}
