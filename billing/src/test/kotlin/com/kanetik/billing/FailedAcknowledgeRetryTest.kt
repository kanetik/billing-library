@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal class FailedAcknowledgeRetryTest {

    @Test
    fun `a failed acknowledge schedules a sweep retry without waiting for a new connect`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.ACKNOWLEDGE, BillingResponseCode.SERVICE_UNAVAILABLE)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<AcknowledgePurchaseParams>(relaxed = true)

        val outcome = timed { repository.acknowledgePurchase(params) }
        assertThat(outcome.result.exceptionOrNull())
            .isInstanceOf(BillingException.ServiceUnavailableException::class.java)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)

        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
    }

    @Test
    fun `a failed consume also schedules a sweep retry`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.CONSUME, BillingResponseCode.SERVICE_UNAVAILABLE)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<ConsumeParams>(relaxed = true)

        val outcome = timed { repository.consumePurchase(params) }
        assertThat(outcome.result.exceptionOrNull())
            .isInstanceOf(BillingException.ServiceUnavailableException::class.java)

        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
    }

    @Test
    fun `a successful acknowledge does not schedule a sweep retry`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<AcknowledgePurchaseParams>(relaxed = true)

        repository.acknowledgePurchase(params)
        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)
    }

    @Test
    fun `a purchase that has failed three times stops scheduling sweeps without blocking another purchase`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.ACKNOWLEDGE, BillingResponseCode.SERVICE_UNAVAILABLE)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val stuck = mockk<AcknowledgePurchaseParams>(relaxed = true) { every { purchaseToken } returns "stuck" }
        val other = mockk<AcknowledgePurchaseParams>(relaxed = true) { every { purchaseToken } returns "other" }

        repeat(4) {
            timed { repository.acknowledgePurchase(stuck) }
            advanceTimeBy(2001)
        }
        val sweepsForStuck = play.calls(Op.QUERY_PURCHASES)

        timed { repository.acknowledgePurchase(other) }
        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(sweepsForStuck + 1)
    }

    @Test
    fun `a failed acknowledge that Play answers ITEM_NOT_OWNED schedules no sweep`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.ACKNOWLEDGE, BillingResponseCode.ITEM_NOT_OWNED)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)

        timed { repository.acknowledgePurchase(mockk<AcknowledgePurchaseParams>(relaxed = true)) }
        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)
    }

    @Test
    fun `consecutive failed acknowledges stop scheduling sweeps after three until an acknowledge succeeds`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(
                Op.ACKNOWLEDGE,
                BillingResponseCode.DEVELOPER_ERROR,
                BillingResponseCode.DEVELOPER_ERROR,
                BillingResponseCode.DEVELOPER_ERROR,
                BillingResponseCode.DEVELOPER_ERROR,
                BillingResponseCode.DEVELOPER_ERROR,
                BillingResponseCode.OK,
                BillingResponseCode.DEVELOPER_ERROR
            )
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<AcknowledgePurchaseParams>(relaxed = true)

        repeat(5) {
            timed { repository.acknowledgePurchase(params) }
            advanceTimeBy(2001)
        }
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(3)

        timed { repository.acknowledgePurchase(params) }
        timed { repository.acknowledgePurchase(params) }
        advanceTimeBy(2001)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(4)
    }

    @Test
    fun `a scheduled sweep retry survives a transient reconnect racing its delay`() = runTest {
        val connections = mutableListOf<MutableSharedFlow<InternalConnectionState>>()
        val factory = object : BillingConnectionFactory {
            override fun createBillingConnectionFlow(
                listener: PurchasesUpdatedListener
            ): Flow<InternalConnectionState> {
                val flow = MutableSharedFlow<InternalConnectionState>(replay = 1, extraBufferCapacity = 4)
                connections += flow
                return flow
            }
        }
        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = false
        )
        backgroundScope.launch { storage.connectionFlow.collect {} }
        runCurrent()

        var queryCalls = 0
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isFeatureSupported(any()) } returns billingResult(BillingResponseCode.FEATURE_NOT_SUPPORTED)
        val listenerSlot = slot<PurchasesResponseListener>()
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(listenerSlot)) } answers {
            queryCalls++
            listenerSlot.captured.onQueryPurchasesResponse(billingResult(BillingResponseCode.OK), emptyList())
        }
        connections[0].tryEmit(InternalConnectionState.Connected(client))
        runCurrent()

        storage.scheduleFailedAcknowledgeRetry("token")

        // A reconnect races the retry's delay: the shared connectionFlow drops back
        // to its transient `null` bootstrap value before the retry's delay elapses.
        advanceTimeBy(1000)
        storage.requestReconnect(InternalConnectionState.Failed(BillingException.ServiceUnavailableException(billingResult(BillingResponseCode.SERVICE_UNAVAILABLE))))
        runCurrent()

        advanceTimeBy(1001)
        assertThat(queryCalls).isEqualTo(0)

        // The reconnect resolves after the delay elapsed; the retry must still sweep.
        connections[1].tryEmit(InternalConnectionState.Connected(client))
        runCurrent()

        assertThat(queryCalls).isEqualTo(1)
    }

    @Test
    fun `a scheduled sweep retry reconnects instead of giving up on an observed Failed state`() = runTest {
        val connections = mutableListOf<MutableSharedFlow<InternalConnectionState>>()
        val factory = object : BillingConnectionFactory {
            override fun createBillingConnectionFlow(
                listener: PurchasesUpdatedListener
            ): Flow<InternalConnectionState> {
                val flow = MutableSharedFlow<InternalConnectionState>(replay = 1, extraBufferCapacity = 4)
                connections += flow
                return flow
            }
        }
        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = false
        )
        backgroundScope.launch { storage.connectionFlow.collect {} }
        runCurrent()

        var queryCalls = 0
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isFeatureSupported(any()) } returns billingResult(BillingResponseCode.FEATURE_NOT_SUPPORTED)
        val listenerSlot = slot<PurchasesResponseListener>()
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(listenerSlot)) } answers {
            queryCalls++
            listenerSlot.captured.onQueryPurchasesResponse(billingResult(BillingResponseCode.OK), emptyList())
        }

        // The connection is already Failed when the retry's delay elapses -- not the
        // transient null bootstrap, a genuine cached failure.
        connections[0].tryEmit(
            InternalConnectionState.Failed(BillingException.ServiceUnavailableException(billingResult(BillingResponseCode.SERVICE_UNAVAILABLE)))
        )
        runCurrent()

        storage.scheduleFailedAcknowledgeRetry("token")
        advanceTimeBy(2001)

        // Observing Failed must drive a reconnect rather than silently giving up.
        assertThat(connections.size).isEqualTo(2)
        assertThat(queryCalls).isEqualTo(0)

        connections[1].tryEmit(InternalConnectionState.Connected(client))
        runCurrent()

        assertThat(queryCalls).isEqualTo(1)
    }
}
