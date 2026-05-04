package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test

class FlowPurchasesUpdatedListenerTest {

    @Test
    fun `OK with all PURCHASED emits a single OwnedPurchases Live`() {
        val (sink, listener) = newListener()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        listener.onPurchasesUpdated(okResult(), listOf(purchase))

        assertThat(sink.replayCache).hasSize(1)
        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(OwnedPurchases.Live::class.java)
        assertThat((event as OwnedPurchases.Live).purchases).containsExactly(purchase)
    }

    @Test
    fun `OK with all PENDING emits a single FlowOutcome Pending`() {
        val (sink, listener) = newListener()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PENDING)

        listener.onPurchasesUpdated(okResult(), listOf(purchase))

        assertThat(sink.replayCache).hasSize(1)
        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.Pending::class.java)
        assertThat((event as FlowOutcome.Pending).purchases).containsExactly(purchase)
    }

    @Test
    fun `OK with mixed PENDING and PURCHASED emits Live then Pending separately`() {
        val (sink, listener) = newListener()
        val settled = fakePurchase(productId = "p1", purchaseState = Purchase.PurchaseState.PURCHASED)
        val pending = fakePurchase(productId = "p2", purchaseState = Purchase.PurchaseState.PENDING)

        listener.onPurchasesUpdated(okResult(), listOf(settled, pending))

        assertThat(sink.replayCache).hasSize(2)
        val (first, second) = sink.replayCache
        assertThat(first).isInstanceOf(OwnedPurchases.Live::class.java)
        assertThat((first as OwnedPurchases.Live).purchases).containsExactly(settled)
        assertThat(second).isInstanceOf(FlowOutcome.Pending::class.java)
        assertThat((second as FlowOutcome.Pending).purchases).containsExactly(pending)
    }

    @Test
    fun `OK with empty purchases emits OwnedPurchases Live with empty list`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(okResult(), emptyList())

        assertThat(sink.replayCache).hasSize(1)
        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(OwnedPurchases.Live::class.java)
        assertThat((event as OwnedPurchases.Live).purchases).isEmpty()
    }

    @Test
    fun `null purchases list is treated as empty`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(okResult(), null)

        assertThat(sink.replayCache).hasSize(1)
        assertThat(sink.replayCache.single()).isInstanceOf(OwnedPurchases.Live::class.java)
    }

    @Test
    fun `USER_CANCELED emits FlowOutcome Canceled`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.USER_CANCELED), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.Canceled::class.java)
    }

    @Test
    fun `ITEM_ALREADY_OWNED emits FlowOutcome ItemAlreadyOwned`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.ITEM_ALREADY_OWNED), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.ItemAlreadyOwned::class.java)
    }

    @Test
    fun `ITEM_UNAVAILABLE emits FlowOutcome ItemUnavailable`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.ITEM_UNAVAILABLE), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.ItemUnavailable::class.java)
    }

    @Test
    fun `NETWORK_ERROR emits FlowOutcome Failure carrying NetworkErrorException`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.NETWORK_ERROR), emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.Failure::class.java)
        val failure = event as FlowOutcome.Failure
        assertThat(failure.exception)
            .isInstanceOf(com.kanetik.billing.exception.BillingException.NetworkErrorException::class.java)
    }

    @Test
    fun `BILLING_UNAVAILABLE emits FlowOutcome Failure carrying BillingUnavailableException`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.BILLING_UNAVAILABLE), emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.Failure::class.java)
        val failure = event as FlowOutcome.Failure
        assertThat(failure.exception)
            .isInstanceOf(com.kanetik.billing.exception.BillingException.BillingUnavailableException::class.java)
    }

    @Test
    fun `truly unknown response code still emits FlowOutcome UnknownResponse with the code preserved`() {
        val (sink, listener) = newListener()
        // 999 is not a real PBL response code and isn't covered by fromResult's
        // explicit mapping — must flow through the UnknownResponse branch.
        listener.onPurchasesUpdated(result(999), emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.UnknownResponse::class.java)
        assertThat((event as FlowOutcome.UnknownResponse).code).isEqualTo(999)
    }

    // Note: testing the "drop logs to error" path is tricky because
    // MutableSharedFlow.tryEmit only returns false when there's an active
    // subscriber AND the buffer overflows — both conditions need a more
    // elaborate setup than is justified for a small log-and-move-on path.
    // The production 32-slot buffer makes drops vanishingly rare; the drop-
    // logging logic is simple enough to read for correctness. Covered via
    // integration testing in :sample if it ever becomes a real concern.

    private fun newListener(): Pair<MutableSharedFlow<PurchaseEvent>, FlowPurchasesUpdatedListener> {
        // replay = 10 so all emissions are inspectable via replayCache after the
        // listener returns; extraBufferCapacity matches production so tryEmit
        // never drops in normal-path tests.
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        return sink to FlowPurchasesUpdatedListener(sink, BillingLogger.Noop)
    }

    private fun okResult(): BillingResult = result(BillingResponseCode.OK)

    private fun result(responseCode: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(responseCode).build()
}
