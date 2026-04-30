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
    fun `OK with all PURCHASED emits a single Success`() {
        val (sink, listener) = newListener()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        listener.onPurchasesUpdated(okResult(), listOf(purchase))

        assertThat(sink.replayCache).hasSize(1)
        val update = sink.replayCache.single()
        assertThat(update).isInstanceOf(PurchasesUpdate.Success::class.java)
        assertThat((update as PurchasesUpdate.Success).purchases).containsExactly(purchase)
    }

    @Test
    fun `OK with all PENDING emits a single Pending`() {
        val (sink, listener) = newListener()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PENDING)

        listener.onPurchasesUpdated(okResult(), listOf(purchase))

        assertThat(sink.replayCache).hasSize(1)
        val update = sink.replayCache.single()
        assertThat(update).isInstanceOf(PurchasesUpdate.Pending::class.java)
        assertThat((update as PurchasesUpdate.Pending).purchases).containsExactly(purchase)
    }

    @Test
    fun `OK with mixed PENDING and PURCHASED emits Success then Pending separately`() {
        val (sink, listener) = newListener()
        val settled = fakePurchase(productId = "p1", purchaseState = Purchase.PurchaseState.PURCHASED)
        val pending = fakePurchase(productId = "p2", purchaseState = Purchase.PurchaseState.PENDING)

        listener.onPurchasesUpdated(okResult(), listOf(settled, pending))

        assertThat(sink.replayCache).hasSize(2)
        val (first, second) = sink.replayCache
        assertThat(first).isInstanceOf(PurchasesUpdate.Success::class.java)
        assertThat((first as PurchasesUpdate.Success).purchases).containsExactly(settled)
        assertThat(second).isInstanceOf(PurchasesUpdate.Pending::class.java)
        assertThat((second as PurchasesUpdate.Pending).purchases).containsExactly(pending)
    }

    @Test
    fun `OK with empty purchases emits Success with empty list`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(okResult(), emptyList())

        assertThat(sink.replayCache).hasSize(1)
        val update = sink.replayCache.single()
        assertThat(update).isInstanceOf(PurchasesUpdate.Success::class.java)
        assertThat((update as PurchasesUpdate.Success).purchases).isEmpty()
    }

    @Test
    fun `null purchases list is treated as empty`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(okResult(), null)

        assertThat(sink.replayCache).hasSize(1)
        assertThat(sink.replayCache.single()).isInstanceOf(PurchasesUpdate.Success::class.java)
    }

    @Test
    fun `USER_CANCELED emits Canceled`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.USER_CANCELED), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(PurchasesUpdate.Canceled::class.java)
    }

    @Test
    fun `ITEM_ALREADY_OWNED emits ItemAlreadyOwned`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.ITEM_ALREADY_OWNED), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(PurchasesUpdate.ItemAlreadyOwned::class.java)
    }

    @Test
    fun `ITEM_UNAVAILABLE emits ItemUnavailable`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.ITEM_UNAVAILABLE), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(PurchasesUpdate.ItemUnavailable::class.java)
    }

    @Test
    fun `unknown response code emits UnknownResponse with the code preserved`() {
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(result(BillingResponseCode.NETWORK_ERROR), emptyList())

        val update = sink.replayCache.single()
        assertThat(update).isInstanceOf(PurchasesUpdate.UnknownResponse::class.java)
        assertThat((update as PurchasesUpdate.UnknownResponse).code).isEqualTo(BillingResponseCode.NETWORK_ERROR)
    }

    // Note: testing the "drop logs to error" path is tricky because
    // MutableSharedFlow.tryEmit only returns false when there's an active
    // subscriber AND the buffer overflows — both conditions need a more
    // elaborate setup than is justified for a small log-and-move-on path.
    // The production 32-slot buffer makes drops vanishingly rare; the drop-
    // logging logic is simple enough to read for correctness. Covered via
    // integration testing in :sample if it ever becomes a real concern.

    private fun newListener(): Pair<MutableSharedFlow<PurchasesUpdate>, FlowPurchasesUpdatedListener> {
        // replay = 10 so all emissions are inspectable via replayCache after the
        // listener returns; extraBufferCapacity matches production so tryEmit
        // never drops in normal-path tests.
        val sink = MutableSharedFlow<PurchasesUpdate>(replay = 10, extraBufferCapacity = 32)
        return sink to FlowPurchasesUpdatedListener(sink, BillingLogger.Noop)
    }

    private fun okResult(): BillingResult = result(BillingResponseCode.OK)

    private fun result(responseCode: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(responseCode).build()
}
