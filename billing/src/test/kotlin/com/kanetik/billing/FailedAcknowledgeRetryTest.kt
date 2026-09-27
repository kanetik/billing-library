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

        storage.scheduleFailedAcknowledgeRetry()

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
}
