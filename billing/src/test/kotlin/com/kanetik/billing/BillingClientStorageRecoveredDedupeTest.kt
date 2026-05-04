package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * Verifies the internal `Recovered` dedupe added by issue #6:
 *
 *  - Purchases whose tokens have been marked as acknowledged via
 *    [BillingClientStorage.markAcknowledged] are filtered out of
 *    [OwnedPurchases.Recovered] emissions.
 *  - When the filter removes every purchase from a sweep, no event
 *    is emitted (rather than `Recovered(emptyList())`).
 *  - When some purchases are acked and some aren't, the emission
 *    contains only the unacked subset.
 *  - A late subscriber attaching after a purchase has been acked does
 *    not see a stale `Recovered` event for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingClientStorageRecoveredDedupeTest {

    @Test
    fun `marked-acknowledged token is filtered out of subsequent Recovered emission`() = runTest {
        val ackedPurchase = fakePurchase(
            productId = "p1",
            purchaseToken = "token-acked",
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false
        )
        val freshPurchase = fakePurchase(
            productId = "p2",
            purchaseToken = "token-fresh",
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false
        )
        val client = mockBillingClient(
            inAppPurchases = listOf(ackedPurchase, freshPurchase),
            subsSupported = false
        )
        val factory = SingleEmissionFactory(InternalConnectionState.Connected(client))

        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = true
        )

        // Mark one as acked BEFORE the sweep runs.
        storage.markAcknowledged("token-acked")

        // Trigger the sweep by subscribing to the connection flow.
        val received = collectFirstRecovered(storage)

        // Only the fresh purchase should reach the subscriber.
        assertThat(received.purchases).containsExactly(freshPurchase)
    }

    @Test
    fun `sweep where every token is already acknowledged emits no event`() = runTest {
        val p1 = fakePurchase(
            productId = "p1",
            purchaseToken = "token-1",
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false
        )
        val p2 = fakePurchase(
            productId = "p2",
            purchaseToken = "token-2",
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false
        )
        val client = mockBillingClient(
            inAppPurchases = listOf(p1, p2),
            subsSupported = false
        )
        val factory = SingleEmissionFactory(InternalConnectionState.Connected(client))

        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = true
        )

        // Pre-mark both tokens.
        storage.markAcknowledged("token-1")
        storage.markAcknowledged("token-2")

        // Drive the connection flow so the sweep runs.
        val collected = mutableListOf<PurchaseEvent>()
        val job = launch {
            storage.purchasesUpdateFlow.collect { collected += it }
        }
        // Force the sweep by also collecting the connection flow.
        val connJob = launch { storage.connectionFlow.collect {} }

        advanceUntilIdle()
        // Brief settle for the sweep emission.
        repeat(3) { yield(); advanceUntilIdle() }

        job.cancel()
        connJob.cancel()

        assertThat(collected).isEmpty()
    }

    @Test
    fun `sweep with one acked and one fresh token emits only the fresh one`() = runTest {
        val acked = fakePurchase(
            productId = "p-acked",
            purchaseToken = "token-acked",
            purchaseState = Purchase.PurchaseState.PURCHASED
        )
        val fresh = fakePurchase(
            productId = "p-fresh",
            purchaseToken = "token-fresh",
            purchaseState = Purchase.PurchaseState.PURCHASED
        )
        val client = mockBillingClient(
            inAppPurchases = listOf(acked, fresh),
            subsSupported = false
        )
        val factory = SingleEmissionFactory(InternalConnectionState.Connected(client))

        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = true
        )

        storage.markAcknowledged("token-acked")

        val emission = collectFirstRecovered(storage)

        assertThat(emission.purchases).containsExactly(fresh)
    }

    @Test
    fun `late subscriber does not see Recovered for token marked acked since last emission`() = runTest {
        // First sweep returns one unacked purchase. Then we mark it as acked.
        // A late subscriber attaching after the mark must not see it.
        val purchase = fakePurchase(
            productId = "p1",
            purchaseToken = "token-only",
            purchaseState = Purchase.PurchaseState.PURCHASED
        )

        // The factory emits Connected once, so the sweep runs exactly once on
        // the first subscriber attach. The replay-1 cache then holds the
        // resulting emission (Recovered(listOf(purchase))).
        val client = mockBillingClient(
            inAppPurchases = listOf(purchase),
            subsSupported = false
        )
        val factory = SingleEmissionFactory(InternalConnectionState.Connected(client))

        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = true
        )

        // First subscriber sees the recovered purchase.
        val firstSeen = collectFirstRecovered(storage)
        assertThat(firstSeen.purchases).containsExactly(purchase)

        // Consumer "handles" it. acknowledgedTokens.value updates, which
        // causes the combine in purchasesUpdateFlow to re-filter the
        // recovery cache for any subscriber.
        storage.markAcknowledged("token-only")

        // Late subscriber attaches. The replay-1 cache on _recoveredUpdates
        // still holds the pre-ack snapshot, but purchasesUpdateFlow combines
        // it with acknowledgedTokens at delivery time — the combine produces
        // Recovered(emptyList()), which filterNot drops, so the late
        // subscriber sees nothing.
        val collectedAfterAck = mutableListOf<PurchaseEvent>()
        val job = launch {
            storage.purchasesUpdateFlow.collect { collectedAfterAck += it }
        }
        // Also drive the connection so the sweep / share-scope stays alive.
        val connJob = launch { storage.connectionFlow.collect {} }
        repeat(3) { yield(); advanceUntilIdle() }

        // Pre-ack stale snapshot is filtered out at delivery time — late
        // subscriber sees zero emissions, not a re-replay of the now-acked
        // purchase that would surface DeveloperErrorException on re-handle.
        assertThat(collectedAfterAck).isEmpty()

        job.cancel()
        connJob.cancel()
    }

    /**
     * Subscribes to [BillingClientStorage.purchasesUpdateFlow] AND to the
     * connection flow (the latter is what actually drives the sweep), waits
     * for the first emission, and returns it.
     */
    private suspend fun TestScope.collectFirstRecovered(
        storage: BillingClientStorage
    ): OwnedPurchases.Recovered {
        // Drive the connection so the sweep runs.
        val connJob = launch { storage.connectionFlow.collect {} }
        return try {
            withTimeout(5_000) {
                storage.purchasesUpdateFlow.first { it is OwnedPurchases.Recovered } as OwnedPurchases.Recovered
            }
        } finally {
            connJob.cancel()
        }
    }

    /**
     * Mocks a [BillingClient] that returns [inAppPurchases] for INAPP queries
     * and an empty list for SUBS (gated by [subsSupported]).
     */
    private fun mockBillingClient(
        inAppPurchases: List<Purchase>,
        subsPurchases: List<Purchase> = emptyList(),
        subsSupported: Boolean = false
    ): BillingClient = mockk<BillingClient>(relaxed = true).also { client ->
        every {
            client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingResult.newBuilder().setResponseCode(
            if (subsSupported) BillingClient.BillingResponseCode.OK
            else BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
        ).build()

        // The suspend extension queryPurchasesAsync(params) wraps the
        // callback variant queryPurchasesAsync(params, listener). Mock the
        // callback variant; mockk can capture the listener and invoke it
        // synchronously.
        //
        // Routing INAPP vs SUBS by call order rather than inspecting params:
        // QueryPurchasesParams doesn't expose its productType publicly in a
        // stable way (relying on toString() was brittle and would break on
        // any PBL string-format change). The sweep code queries INAPP first
        // and SUBS second; the async parallel-await pattern doesn't change
        // the order the mock is called in. If subsSupported = false, the
        // sweep skips the SUBS call entirely, so the second invocation never
        // happens.
        val listenerSlot = slot<PurchasesResponseListener>()
        var callIndex = 0
        every {
            client.queryPurchasesAsync(any(), capture(listenerSlot))
        } answers {
            val listener = listenerSlot.captured
            val purchasesForCall = if (callIndex == 0) inAppPurchases else subsPurchases
            callIndex++
            val ok = BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
            listener.onQueryPurchasesResponse(ok, purchasesForCall)
        }
    }

    /**
     * Minimal [BillingConnectionFactory] that emits a single connection state
     * (or more, via [emit]) into a hot SharedFlow. The provided
     * [PurchasesUpdatedListener] is captured but not driven — these tests
     * exercise the recovery sweep, not the live channel.
     */
    private class SingleEmissionFactory(
        private val initialState: InternalConnectionState
    ) : BillingConnectionFactory {
        private val flow = MutableSharedFlow<InternalConnectionState>(replay = 1, extraBufferCapacity = 4)

        init {
            // Replay-1 ensures the initial state is immediately available to
            // the first subscriber.
            check(flow.tryEmit(initialState))
        }

        override fun createBillingConnectionFlow(
            listener: PurchasesUpdatedListener
        ): Flow<InternalConnectionState> = flow

        suspend fun emit(state: InternalConnectionState) {
            flow.emit(state)
        }
    }
}
