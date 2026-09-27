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
        val other = fakePurchase()
        val a = FlowOutcome.Pending(listOf(purchase), result(BillingResponseCode.OK))
        val b = FlowOutcome.Pending(listOf(purchase), result(BillingResponseCode.OK))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(FlowOutcome.Pending(listOf(other), result(BillingResponseCode.OK)))
    }

    @Test
    fun `Canceled instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val other = fakePurchase()
        val a = FlowOutcome.Canceled(listOf(purchase), result(BillingResponseCode.USER_CANCELED))
        val b = FlowOutcome.Canceled(listOf(purchase), result(BillingResponseCode.USER_CANCELED))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(FlowOutcome.Canceled(listOf(other), result(BillingResponseCode.USER_CANCELED)))
    }

    @Test
    fun `ItemAlreadyOwned instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val other = fakePurchase()
        val a = FlowOutcome.ItemAlreadyOwned(listOf(purchase), result(BillingResponseCode.ITEM_ALREADY_OWNED))
        val b = FlowOutcome.ItemAlreadyOwned(listOf(purchase), result(BillingResponseCode.ITEM_ALREADY_OWNED))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(
            FlowOutcome.ItemAlreadyOwned(listOf(other), result(BillingResponseCode.ITEM_ALREADY_OWNED))
        )
    }

    @Test
    fun `ItemUnavailable instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val other = fakePurchase()
        val a = FlowOutcome.ItemUnavailable(listOf(purchase), result(BillingResponseCode.ITEM_UNAVAILABLE))
        val b = FlowOutcome.ItemUnavailable(listOf(purchase), result(BillingResponseCode.ITEM_UNAVAILABLE))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(
            FlowOutcome.ItemUnavailable(listOf(other), result(BillingResponseCode.ITEM_UNAVAILABLE))
        )
    }

    @Test
    fun `UserBillingError instances with equal purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val other = fakePurchase()
        val a = FlowOutcome.UserBillingError(listOf(purchase), result(BillingResponseCode.BILLING_UNAVAILABLE))
        val b = FlowOutcome.UserBillingError(listOf(purchase), result(BillingResponseCode.BILLING_UNAVAILABLE))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(
            FlowOutcome.UserBillingError(listOf(other), result(BillingResponseCode.BILLING_UNAVAILABLE))
        )
    }

    @Test
    fun `Failure instances with the same exception and purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val other = fakePurchase()
        val exception = BillingException.fromResult(result(BillingResponseCode.NETWORK_ERROR))
        val otherException = BillingException.fromResult(result(BillingResponseCode.SERVICE_UNAVAILABLE))
        val a = FlowOutcome.Failure(exception, listOf(purchase), result(BillingResponseCode.NETWORK_ERROR))
        val b = FlowOutcome.Failure(exception, listOf(purchase), result(BillingResponseCode.NETWORK_ERROR))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(FlowOutcome.Failure(exception, listOf(other), result(BillingResponseCode.NETWORK_ERROR)))
        assertThat(a).isNotEqualTo(
            FlowOutcome.Failure(otherException, listOf(purchase), result(BillingResponseCode.NETWORK_ERROR))
        )
    }

    @Test
    fun `UnknownResponse instances with equal code and purchases but distinct BillingResult instances are equal`() {
        val purchase = fakePurchase()
        val other = fakePurchase()
        val a = FlowOutcome.UnknownResponse(999, listOf(purchase), result(999))
        val b = FlowOutcome.UnknownResponse(999, listOf(purchase), result(999))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(FlowOutcome.UnknownResponse(999, listOf(other), result(999)))
        assertThat(a).isNotEqualTo(FlowOutcome.UnknownResponse(998, listOf(purchase), result(999)))
    }

    @Test
    fun `FlowOutcome variants with the same purchases and result are not equal across types`() {
        val purchase = fakePurchase()
        val r = result(BillingResponseCode.OK)

        assertThat(FlowOutcome.Pending(listOf(purchase), r) as Any)
            .isNotEqualTo(FlowOutcome.Canceled(listOf(purchase), r))
        assertThat(FlowOutcome.ItemAlreadyOwned(listOf(purchase), r) as Any)
            .isNotEqualTo(FlowOutcome.ItemUnavailable(listOf(purchase), r))
        assertThat(FlowOutcome.UserBillingError(listOf(purchase), r) as Any)
            .isNotEqualTo(FlowOutcome.UnknownResponse(0, listOf(purchase), r))
    }

    private fun result(responseCode: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(responseCode).build()
}
