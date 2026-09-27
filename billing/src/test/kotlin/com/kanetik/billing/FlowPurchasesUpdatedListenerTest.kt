@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.OnPurchasesUpdatedSubResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun `OK with all PENDING emits a single FlowOutcome Pending carrying the result`() {
        val (sink, listener) = newListener()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PENDING)
        val r = okResult()

        listener.onPurchasesUpdated(r, listOf(purchase))

        assertThat(sink.replayCache).hasSize(1)
        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.Pending::class.java)
        assertThat((event as FlowOutcome.Pending).purchases).containsExactly(purchase)
        assertThat(event.result).isSameInstanceAs(r)
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
    fun `OK with empty purchases drops the event entirely (no empty Live forwarded)`() {
        // Issue #13: PBL occasionally fires onPurchasesUpdated with literally
        // nothing. The listener used to forward Live(emptyList()) for symmetry
        // with prior contracts, but consumers writing event.purchases into an
        // entitlement cache silently wiped state. Empty Live carries no
        // actionable signal (Pending purchases route through FlowOutcome.Pending),
        // so the event is dropped at the source.
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(okResult(), emptyList())

        assertThat(sink.replayCache).isEmpty()
    }

    @Test
    fun `null purchases list is treated as empty and produces no event`() {
        // Same drop-at-source contract as the empty-list case above —
        // PurchasesUpdatedListener.onPurchasesUpdated is nullable, and a null
        // payload is normalized to an empty list internally.
        val (sink, listener) = newListener()
        listener.onPurchasesUpdated(okResult(), null)

        assertThat(sink.replayCache).isEmpty()
    }

    @Test
    fun `USER_CANCELED emits FlowOutcome Canceled carrying the result`() {
        val (sink, listener) = newListener()
        val r = result(BillingResponseCode.USER_CANCELED)

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.Canceled::class.java)
        assertThat((event as FlowOutcome.Canceled).result).isSameInstanceAs(r)
    }

    @Test
    fun `ITEM_ALREADY_OWNED emits FlowOutcome ItemAlreadyOwned carrying the result`() {
        val (sink, listener) = newListener()
        val r = result(BillingResponseCode.ITEM_ALREADY_OWNED)

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.ItemAlreadyOwned::class.java)
        assertThat((event as FlowOutcome.ItemAlreadyOwned).result).isSameInstanceAs(r)
    }

    @Test
    fun `ITEM_UNAVAILABLE emits FlowOutcome ItemUnavailable carrying the result`() {
        val (sink, listener) = newListener()
        val r = result(BillingResponseCode.ITEM_UNAVAILABLE)

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.ItemUnavailable::class.java)
        assertThat((event as FlowOutcome.ItemUnavailable).result).isSameInstanceAs(r)
    }

    @Test
    fun `NETWORK_ERROR emits FlowOutcome Failure carrying NetworkErrorException and the result`() {
        val (sink, listener) = newListener()
        val r = result(BillingResponseCode.NETWORK_ERROR)

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.Failure::class.java)
        val failure = event as FlowOutcome.Failure
        assertThat(failure.exception)
            .isInstanceOf(com.kanetik.billing.exception.BillingException.NetworkErrorException::class.java)
        assertThat(failure.result).isSameInstanceAs(r)
    }

    @Test
    fun `BILLING_UNAVAILABLE from a purchase flow emits FlowOutcome UserBillingError, not Failure`() {
        val (sink, listener) = newListener()
        val r = result(BillingResponseCode.BILLING_UNAVAILABLE)

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.UserBillingError::class.java)
        assertThat((event as FlowOutcome.UserBillingError).result).isSameInstanceAs(r)
    }

    @Test
    fun `BILLING_UNAVAILABLE carries the insufficient-funds sub-response code on UserBillingError`() {
        val (sink, listener) = newListener()
        val r = result(
            BillingResponseCode.BILLING_UNAVAILABLE,
            subResponseCode = OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS
        )

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single() as FlowOutcome.UserBillingError
        assertThat(event.result.onPurchasesUpdatedSubResponseCode)
            .isEqualTo(OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS)
    }

    @Test
    fun `BILLING_UNAVAILABLE with insufficient-funds sub-response is logged once at debug, not warn`() = runTest {
        val captor = CapturingLogger()
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        val listener = FlowPurchasesUpdatedListener(sink, captor)
        backgroundScope.launch { sink.collect {} }
        runCurrent()
        val r = result(
            BillingResponseCode.BILLING_UNAVAILABLE,
            subResponseCode = OnPurchasesUpdatedSubResponseCode.PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS
        )

        listener.onPurchasesUpdated(r, emptyList())

        assertThat(captor.warnings).isEmpty()
        assertThat(captor.debugs).hasSize(1)
        assertThat(captor.debugs.single()).contains("Insufficient funds")
    }

    @Test
    fun `onPurchasesUpdated logs the result exactly once at debug with code, sub-response and debug message`() = runTest {
        val captor = CapturingLogger()
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        val listener = FlowPurchasesUpdatedListener(sink, captor)
        backgroundScope.launch { sink.collect {} }
        runCurrent()
        val r = result(
            BillingResponseCode.NETWORK_ERROR,
            subResponseCode = OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE
        )

        listener.onPurchasesUpdated(r, emptyList())

        assertThat(captor.debugs).hasSize(1)
        val logged = captor.debugs.single()
        assertThat(logged).contains("Network Error")
        assertThat(captor.warnings).isEmpty()
        assertThat(captor.errors).isEmpty()
    }

    @Test
    fun `onPurchasesUpdated warns when emitted with no active observePurchaseUpdates collector`() {
        val captor = CapturingLogger()
        val listener = FlowPurchasesUpdatedListener(MutableSharedFlow(replay = 10, extraBufferCapacity = 32), captor)
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        listener.onPurchasesUpdated(okResult(), listOf(purchase))

        assertThat(captor.warnings.any { it.contains("no active") }).isTrue()
    }

    @Test
    fun `onPurchasesUpdated never logs a purchase token`() {
        val captor = CapturingLogger()
        val listener = FlowPurchasesUpdatedListener(MutableSharedFlow(replay = 10, extraBufferCapacity = 32), captor)
        val token = "secret-purchase-token-should-never-be-logged"
        val purchase = fakePurchase(purchaseToken = token, purchaseState = Purchase.PurchaseState.PURCHASED)

        listener.onPurchasesUpdated(okResult(), listOf(purchase))

        assertThat(captor.debugs.any { it.contains(token) }).isFalse()
        assertThat(captor.warnings.any { it.contains(token) }).isFalse()
        assertThat(captor.errors.any { it.contains(token) }).isFalse()
    }

    @Test
    fun `truly unknown response code still emits FlowOutcome UnknownResponse with the code and result preserved`() {
        val (sink, listener) = newListener()
        // 999 is not a real PBL response code and isn't covered by fromResult's
        // explicit mapping — must flow through the UnknownResponse branch.
        val r = result(999)

        listener.onPurchasesUpdated(r, emptyList())

        val event = sink.replayCache.single()
        assertThat(event).isInstanceOf(FlowOutcome.UnknownResponse::class.java)
        assertThat((event as FlowOutcome.UnknownResponse).code).isEqualTo(999)
        assertThat(event.result).isSameInstanceAs(r)
    }

    @Test
    fun `onPurchasesUpdated drops a matching echo inside the suppression window and logs it at debug`() {
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        val captor = CapturingLogger()
        var now = 0L
        val suppression = LaunchFailureSuppression { now }
        val listener = FlowPurchasesUpdatedListener(sink, captor, suppression)
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 12

        listener.onPurchasesUpdated(result(BillingResponseCode.BILLING_UNAVAILABLE), null)

        assertThat(sink.replayCache).isEmpty()
        assertThat(captor.debugs.single()).contains("suppressed echo")
    }

    @Test
    fun `onPurchasesUpdated emits normally when no launch failure is armed`() {
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        val listener = FlowPurchasesUpdatedListener(sink, BillingLogger.Noop, LaunchFailureSuppression { 0L })

        listener.onPurchasesUpdated(result(BillingResponseCode.BILLING_UNAVAILABLE), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.UserBillingError::class.java)
    }

    @Test
    fun `onPurchasesUpdated emits normally when the armed code does not match`() {
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        var now = 0L
        val suppression = LaunchFailureSuppression { now }
        val listener = FlowPurchasesUpdatedListener(sink, BillingLogger.Noop, suppression)
        suppression.arm(BillingResponseCode.ITEM_ALREADY_OWNED)
        now += 12

        listener.onPurchasesUpdated(result(BillingResponseCode.BILLING_UNAVAILABLE), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.UserBillingError::class.java)
    }

    @Test
    fun `onPurchasesUpdated emits an unrelated code inside the window and still suppresses the matching echo that follows`() {
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        val captor = CapturingLogger()
        var now = 0L
        val suppression = LaunchFailureSuppression { now }
        val listener = FlowPurchasesUpdatedListener(sink, captor, suppression)
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 12

        listener.onPurchasesUpdated(result(BillingResponseCode.ITEM_ALREADY_OWNED), emptyList())
        now += 12
        listener.onPurchasesUpdated(result(BillingResponseCode.BILLING_UNAVAILABLE), null)

        assertThat(sink.replayCache).hasSize(1)
        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.ItemAlreadyOwned::class.java)
        assertThat(captor.debugs.any { it.contains("suppressed echo") }).isTrue()
    }

    @Test
    fun `onPurchasesUpdated emits normally once the suppression window has elapsed`() {
        val sink = MutableSharedFlow<PurchaseEvent>(replay = 10, extraBufferCapacity = 32)
        var now = 0L
        val suppression = LaunchFailureSuppression { now }
        val listener = FlowPurchasesUpdatedListener(sink, BillingLogger.Noop, suppression)
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 501

        listener.onPurchasesUpdated(result(BillingResponseCode.BILLING_UNAVAILABLE), emptyList())

        assertThat(sink.replayCache.single()).isInstanceOf(FlowOutcome.UserBillingError::class.java)
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

    private fun result(
        responseCode: Int,
        subResponseCode: Int = OnPurchasesUpdatedSubResponseCode.NO_APPLICABLE_SUB_RESPONSE_CODE
    ): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(responseCode)
            .setOnPurchasesUpdatedSubResponseCode(subResponseCode)
            .build()

    private class CapturingLogger : BillingLogger {
        val debugs = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) {
            debugs += message
        }
        override fun w(message: String, throwable: Throwable?) {
            warnings += message
        }
        override fun e(message: String, throwable: Throwable?) {
            errors += message
        }
    }
}
